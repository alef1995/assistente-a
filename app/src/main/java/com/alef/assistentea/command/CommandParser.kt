package com.alef.assistentea.command

import java.text.Normalizer
import java.util.Locale

/**
 * Converte o texto reconhecido (português do Brasil) em um [Command].
 *
 * Funciona por palavras-chave, sem IA: é rápido, previsível e funciona offline.
 *
 * Para errar menos, antes de procurar as palavras-chave a frase passa por três correções:
 *  1. "A" grudado no comando ("atocar", "apausar", "aproxima") é separado;
 *  2. erros comuns do reconhecedor viram a palavra certa ("posar" → "pausar");
 *  3. palavras de comando com uma letra trocada são aproximadas ("pausr" → "pausar").
 * O nome da música, depois de "tocar", é sempre usado exatamente como foi ouvido.
 */
object CommandParser {

    // Formas como o reconhecedor costuma transcrever a palavra de ativação "A".
    private val WAKE_PREFIXES = listOf(
        listOf("ei", "a"), listOf("hey", "a"), listOf("hei", "a"), listOf("oi", "a"), listOf("ok", "a"),
        listOf("e", "a"), listOf("ai", "a"), listOf("assistente"), listOf("eia"), listOf("heia"),
        listOf("a"), listOf("ah"), listOf("ha"), listOf("aa"), listOf("aah"), listOf("ahh")
    )

    // Verbos que iniciam um pedido de música.
    private val PLAY_PREFIXES = listOf(
        listOf("eu", "quero", "ouvir"), listOf("quero", "ouvir"), listOf("quero", "escutar"),
        listOf("toca", "ai"), listOf("toca", "pra", "mim"), listOf("toca", "para", "mim"),
        listOf("tocar"), listOf("toca"), listOf("toque"), listOf("tocando"),
        listOf("reproduzir"), listOf("reproduz"), listOf("reproduza"),
        listOf("colocar"), listOf("coloca"), listOf("coloque"),
        listOf("botar"), listOf("bota"), listOf("bote"), listOf("poe"),
        listOf("ouvir"), listOf("escutar"), listOf("play")
    )

    private val OPEN_PREFIXES = listOf(listOf("abrir", "o"), listOf("abre", "o"), listOf("abra", "o"),
        listOf("abrir", "a"), listOf("abre", "a"), listOf("abra", "a"),
        listOf("abrir"), listOf("abre"), listOf("abra"), listOf("abri"), listOf("iniciar"), listOf("inicia"))

    /** Se aparecerem logo depois de "coloca/bota", o pedido não é de música. */
    private val NOT_MUSIC = setOf("volume", "som", "timer", "temporizador", "alarme", "despertador", "lanterna", "cronometro")

    // Palavras que, sozinhas depois de "tocar", significam "continuar".
    private val RESUME_ONLY = setOf("", "musica", "a musica", "de novo", "novamente", "o som", "som")
    // ... ou "próxima"
    private val NEXT_ONLY = setOf("a proxima", "proxima", "a proxima musica", "proxima musica", "outra", "outra musica", "a seguinte")

    /** Palavras de comando conhecidas (usadas na correção de erros de reconhecimento). */
    private val VOCAB = setOf(
        "tocar", "toca", "toque", "tocando", "colocar", "coloca", "coloque", "reproduzir", "reproduza", "escutar", "ouvir",
        "pausar", "pausa", "pause", "despausar", "despausa", "continuar", "continua", "continue", "retomar", "retoma",
        "parar", "para", "proxima", "proximo", "anterior", "pular", "pula", "passar", "passa", "avancar", "avanca",
        "seguinte", "trocar", "troca", "musica", "musicas", "volume", "aumentar", "aumenta", "aumente", "diminuir",
        "diminui", "diminua", "abaixar", "abaixa", "abaixe", "baixar", "baixa", "silenciar", "silencia", "mudo",
        "desmutar", "maximo", "minimo", "metade", "lanterna", "ligar", "liga", "desligar", "desliga", "apagar", "apaga",
        "acender", "acende", "bateria", "carga", "horas", "hora", "despertador", "alarme", "acorda", "acordar",
        "timer", "temporizador", "cronometro", "minutos", "minuto", "segundos", "segundo", "abrir", "abre", "abra",
        "ajuda", "comandos", "piada", "obrigado", "obrigada", "valeu", "cancela", "cancelar", "esquece", "dormir",
        "tchau", "ouvir", "escuta", "spotify", "youtube", "deezer", "artista", "cantor", "cantora", "banda",
        "repete", "repetir", "hoje", "data", "agora", "tocando", "quanto", "qual", "baixo", "alto"
    )

    /** Erros frequentes do reconhecedor de voz → palavra certa. */
    private val ALIASES = mapOf(
        "posar" to "pausar", "pousar" to "pausar", "pausa" to "pausa", "pauza" to "pausa", "pauzar" to "pausar",
        "pozar" to "pausar", "pousa" to "pausa", "paus" to "pausa",
        "proxim" to "proxima", "proximaa" to "proxima", "procima" to "proxima", "prossima" to "proxima",
        "torcar" to "tocar", "tokar" to "tocar", "tocah" to "tocar", "tocca" to "toca",
        "valume" to "volume", "volum" to "volume", "volumi" to "volume", "volumes" to "volume",
        "aumentá" to "aumentar", "aumentaa" to "aumentar", "almentar" to "aumentar", "almenta" to "aumenta",
        "diminuí" to "diminui", "deminuir" to "diminuir", "deminui" to "diminui",
        "lanternaa" to "lanterna", "lanterninha" to "lanterna",
        "taimer" to "timer", "taimar" to "timer", "taime" to "timer", "time" to "timer",
        "despertadô" to "despertador", "alarm" to "alarme",
        "brigado" to "obrigado", "brigada" to "obrigada"
    )

    /** Escolhe a melhor alternativa do reconhecedor: a primeira que vira um comando conhecido,
     *  preferindo as que começam com a palavra de ativação se [preferWake]. */
    fun parseBest(alternatives: List<String>, preferWake: Boolean = false): ParseResult {
        val parsed = alternatives.map { parse(it) }
        val known = parsed.filter { it.command !is Command.Unknown }
        if (preferWake) known.firstOrNull { it.hadWakeWord }?.let { return it }
        return known.firstOrNull() ?: parsed.firstOrNull() ?: ParseResult(Command.Unknown(""), false)
    }

    fun parse(raw: String): ParseResult {
        // Tokens originais (com acento) e normalizados (sem acento, minúsculos), alinhados 1:1.
        val original = Regex("[\\p{L}\\p{N}%]+").findAll(raw).map { it.value }.toMutableList()
        val norm = original.map { normalize(it) }.toMutableList()
        // "50%" vira "50" e "%"; "por cento" vira "%"; "7h30" vira "7" "30"
        splitPercent(original, norm)
        splitHours(original, norm)
        // "atocar" → "a tocar" (só no começo da frase ou depois de "ei"/"oi")
        splitGluedWake(original, norm)

        // Correção dos erros conhecidos e de pequenas trocas de letras (só para achar o comando)
        val fixed = norm.map { fix(it) }.toMutableList()

        var start = 0
        var hadWake = false
        for (prefix in WAKE_PREFIXES) {
            if (startsWith(fixed, 0, prefix)) {
                start = prefix.size
                hadWake = true
                break
            }
        }
        if (hadWake && start >= fixed.size) return ParseResult(Command.WakeOnly, true)

        val tokens = fixed.subList(start, fixed.size)
        val origTokens = original.subList(start, original.size)
        val text = tokens.joinToString(" ")
        if (text.isBlank()) return ParseResult(Command.Unknown(raw), hadWake)

        return ParseResult(match(tokens, origTokens, text), hadWake)
    }

    private fun match(tokens: List<String>, orig: List<String>, text: String): Command {
        // 1) Pedido de música vem primeiro: o nome da música pode conter qualquer palavra
        //    ("tocar Próxima Parada", "tocar Volta pra Mim").
        for (prefix in PLAY_PREFIXES) {
            if (startsWith(tokens, 0, prefix)) {
                val rest = tokens.drop(prefix.size)
                if (rest.joinToString(" ") in RESUME_ONLY) return Command.Resume
                // "coloca o volume em 30", "coloca um timer" não são pedidos de música
                if (rest.take(3).any { it in NOT_MUSIC }) break
                return playCommand(tokens.drop(prefix.size), orig.drop(prefix.size))
            }
        }

        // 2) Abrir aplicativo
        for (prefix in OPEN_PREFIXES) {
            if (startsWith(tokens, 0, prefix) && tokens.size > prefix.size) {
                val name = orig.drop(prefix.size).joinToString(" ").trim()
                if (tokens.drop(prefix.size).any { it == "lanterna" }) return Command.Flashlight(true)
                return Command.OpenApp(name)
            }
        }

        // 3) Parar de ouvir (antes de "parar")
        if (has(text, "parar de ouvir", "para de ouvir", "pare de ouvir", "parar de escutar", "para de escutar",
                "pare de escutar", "desligar a escuta", "desliga a escuta", "desligar escuta", "desliga escuta",
                "pode dormir", "vai dormir", "dormir", "tchau", "ate mais", "ate logo",
                "desligar assistente", "desliga assistente", "desligar a assistente", "desliga a assistente")) {
            return Command.StopListening
        }

        // 4) Temporizador
        if (has(text, "timer", "temporizador", "cronometro", "me avisa em", "me avisa daqui", "me lembra em",
                "me lembra daqui", "contagem regressiva")) {
            return Command.Timer(parseDuration(tokens))
        }

        // 5) Despertador
        if (has(text, "despertador", "alarme", "me acorda", "me acordar", "despertar")) {
            val (h, m) = parseClock(tokens) ?: (-1 to -1)
            return Command.Alarm(h, m)
        }

        // 6) Lanterna
        if (has(text, "lanterna", "flash")) {
            val off = has(text, "desliga", "desligar", "desligue", "apaga", "apagar", "apague", "tira", "tirar")
            return Command.Flashlight(!off)
        }

        // 7) Volume
        if (has(text, "qual o volume", "qual volume", "que volume", "quanto ta o volume", "quanto esta o volume",
                "como ta o volume", "volume atual")) {
            return Command.VolumeQuery
        }
        if (has(text, "volume", "som")) {
            parseVolumeNumber(tokens)?.let { return Command.SetVolume(it.coerceIn(0, 100)) }
            if (has(text, "maximo", "no talo", "tudo", "no maximo")) return Command.SetVolume(100)
            if (has(text, "minimo")) return Command.SetVolume(10)
            if (has(text, "metade", "meio")) return Command.SetVolume(50)
        }
        if (tokens.size <= 3 && tokens.contains("%")) {
            parseVolumeNumber(tokens)?.let { return Command.SetVolume(it.coerceIn(0, 100)) }
        }

        // 8) Mudo
        if (has(text, "tirar do mudo", "tira do mudo", "desmutar", "desmuta", "desativar o mudo",
                "desativa o mudo", "ativar o som", "ativa o som", "religar o som", "liga o som", "volta o som")) {
            return Command.Unmute
        }
        if (has(text, "silenciar", "silencia", "silencie", "silencio", "mudo", "mutar", "muta", "mute")) {
            return Command.Mute
        }

        // 9) Aumentar / diminuir
        val mentionsVolume = has(text, "volume", "som", "audio")
        if (has(text, "mais alto", "aumenta mais", "aumente", "aumenta ai", "sobe o som", "sobe o volume") ||
            (mentionsVolume && has(text, "aumentar", "aumenta", "sobe", "subir", "suba", "levanta"))) {
            return Command.VolumeUp
        }
        if (has(text, "mais baixo", "abaixa mais", "abaixe", "abaixa ai", "baixa ai") ||
            (mentionsVolume && has(text, "diminuir", "diminui", "diminua", "abaixar", "abaixa",
                "baixa", "baixar", "reduzir", "reduz", "desce", "descer"))) {
            return Command.VolumeDown
        }
        if (text == "aumentar" || text == "aumenta") return Command.VolumeUp
        if (text in setOf("diminuir", "diminui", "abaixar", "abaixa", "baixa", "baixar")) return Command.VolumeDown

        // 10) Qual música está tocando? (antes de "próxima"/"parar" por causa de "tocando")
        if (has(text, "que musica e essa", "qual musica", "que musica", "o que esta tocando",
                "o que ta tocando", "nome da musica", "que som e esse", "quem canta", "que musica ta tocando")) {
            return Command.NowPlaying
        }

        // 11) Hora, data, bateria
        if (has(text, "que horas", "que hora", "horas sao", "me diz as horas", "hora certa", "diz a hora",
                "fala a hora", "fala as horas") || text == "horas" || text == "hora") {
            return Command.Time
        }
        if (has(text, "que dia e hoje", "que dia hoje", "qual a data", "qual e a data", "data de hoje",
                "dia e hoje", "que dia e", "que dia da semana", "qual o dia")) {
            return Command.Date
        }
        if (has(text, "bateria", "carga do celular", "carga do telefone")) return Command.Battery

        // 12) Navegação
        if (has(text, "anterior", "volta a musica", "voltar a musica", "volta uma", "voltar uma",
                "musica de antes", "a de antes", "volta essa", "volta pra anterior")) {
            return Command.Previous
        }
        if (has(text, "proxima", "proximo", "pula", "pular", "pule", "passa", "passar", "passe",
                "avanca", "avancar", "seguinte", "troca a musica", "trocar a musica", "troca de musica",
                "outra musica", "muda a musica", "mudar a musica", "troca essa")) {
            return Command.Next
        }

        // 13) Continuar (antes de pausar: "despausar" contém "pausar")
        if (has(text, "despausar", "despausa", "continuar", "continua", "continue", "retomar", "retoma",
                "volta a tocar", "voltar a tocar", "pode tocar", "solta o som", "play", "resume", "solta a musica")) {
            return Command.Resume
        }

        // 14) Pausar / parar
        if (has(text, "pausar", "pausa", "pause", "pausado", "da um pause", "segura", "da uma pausa")) {
            return Command.Pause
        }
        if (has(text, "parar", "para a musica", "para de tocar", "para musica", "para o som",
                "para tudo", "stop", "desliga a musica", "desligar a musica", "chega", "pare") || text == "para") {
            return Command.Stop
        }

        // 15) Conversa
        if (has(text, "piada", "me faz rir", "algo engracado", "me faca rir")) return Command.Joke
        if (has(text, "obrigado", "obrigada", "valeu", "agradeco", "muito obrigado")) return Command.Thanks
        if (tokens.size <= 4 && has(text, "bom dia", "boa tarde", "boa noite", "oi", "ola", "tudo bem",
                "e ai", "como voce esta", "como vai", "beleza")) {
            return Command.Greeting
        }
        if (text in setOf("cancela", "cancelar", "nada", "deixa", "deixa pra la", "esquece", "nao", "nao nada",
                "deixa quieto", "esquece isso", "nada nao", "deixa para la", "esquece ai")) {
            return Command.Cancel
        }

        // 16) Ajuda
        if (has(text, "ajuda", "o que voce faz", "o que voce sabe", "comandos", "socorro", "o que voce pode fazer")) {
            return Command.Help
        }

        return Command.Unknown(orig.joinToString(" "))
    }

    private fun playCommand(rest: List<String>, restOrig: List<String>): Command {
        var t = rest
        var o = restOrig

        // Remove sufixos como "no Spotify", "no YouTube"
        val appSuffix = listOf("no spotify", "no youtube", "no youtube music", "no deezer", "pra mim", "para mim", "por favor", "ai")
        var changed = true
        while (changed) {
            changed = false
            for (s in appSuffix) {
                val parts = s.split(" ")
                if (t.size > parts.size && t.takeLast(parts.size) == parts) {
                    t = t.dropLast(parts.size); o = o.dropLast(parts.size); changed = true
                }
            }
        }

        val joined = t.joinToString(" ")
        if (joined in RESUME_ONLY) return Command.Resume
        if (joined in NEXT_ONLY) return Command.Next

        // "músicas do Henrique e Juliano", "algo da Marília Mendonça", "um pouco de Matuê"
        val artistPrefixes = listOf(
            listOf("as", "musicas", "do"), listOf("as", "musicas", "da"), listOf("as", "musicas", "de"),
            listOf("as", "musicas", "dos"), listOf("as", "musicas", "das"),
            listOf("umas", "musicas", "do"), listOf("umas", "musicas", "da"), listOf("umas", "musicas", "de"),
            listOf("musicas", "do"), listOf("musicas", "da"), listOf("musicas", "de"),
            listOf("musicas", "dos"), listOf("musicas", "das"),
            listOf("musica", "do"), listOf("musica", "da"), listOf("musica", "de"),
            listOf("algo", "do"), listOf("algo", "da"), listOf("algo", "de"),
            listOf("alguma", "coisa", "do"), listOf("alguma", "coisa", "da"), listOf("alguma", "coisa", "de"),
            listOf("um", "pouco", "do"), listOf("um", "pouco", "da"), listOf("um", "pouco", "de"),
            listOf("o", "artista"), listOf("a", "artista"), listOf("a", "banda"), listOf("o", "cantor"), listOf("a", "cantora")
        )
        for (p in artistPrefixes) {
            if (t.size > p.size && startsWith(t, 0, p)) {
                val artist = o.drop(p.size).joinToString(" ")
                return Command.Play(query = artist, artist = artist)
            }
        }

        // "a música Evidências", "música Evidências"
        val songPrefixes = listOf(listOf("a", "musica"), listOf("musica"), listOf("a", "cancao"))
        for (p in songPrefixes) {
            if (t.size > p.size && startsWith(t, 0, p)) {
                return Command.Play(query = o.drop(p.size).joinToString(" "))
            }
        }

        val query = o.joinToString(" ").replace(" %", "%").trim()
        if (query.isEmpty()) return Command.Resume
        return Command.Play(query = query)
    }

    // ------------------------------------------------------------------ números

    private val UNITS = mapOf(
        "zero" to 0, "um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3, "quatro" to 4,
        "cinco" to 5, "seis" to 6, "sete" to 7, "oito" to 8, "nove" to 9, "dez" to 10, "onze" to 11,
        "doze" to 12, "treze" to 13, "catorze" to 14, "quatorze" to 14, "quinze" to 15,
        "dezesseis" to 16, "dezessete" to 17, "dezoito" to 18, "dezenove" to 19
    )
    private val TENS = mapOf(
        "vinte" to 20, "trinta" to 30, "quarenta" to 40, "cinquenta" to 50, "sessenta" to 60,
        "setenta" to 70, "oitenta" to 80, "noventa" to 90, "cem" to 100, "cento" to 100
    )

    /** Procura um número (dígitos ou por extenso) na frase. */
    internal fun parseVolumeNumber(tokens: List<String>): Int? {
        tokens.firstOrNull { it.all(Char::isDigit) && it.isNotEmpty() }?.let { return it.toIntOrNull() }

        var total = 0
        var found = false
        var i = 0
        while (i < tokens.size) {
            val w = tokens[i]
            val v = TENS[w] ?: UNITS[w]
            if (v != null) {
                // ignora "um"/"uma" como artigo ("um pouco"), a menos que seja o único número
                if ((w == "um" || w == "uma") && !found && i + 1 < tokens.size && tokens[i + 1] !in setOf("%", "por")) {
                    i++; continue
                }
                total += v
                found = true
            } else if (w == "e" && found) {
                // "trinta e cinco"
            } else if (found) {
                break
            }
            i++
        }
        return if (found) total else null
    }

    /** Lê um número começando em [from]: "25", "vinte e cinco", "uma". Retorna (valor, próximo índice). */
    private fun readNumber(tokens: List<String>, from: Int): Pair<Int, Int>? {
        if (from >= tokens.size) return null
        val first = tokens[from]
        if (first.isNotEmpty() && first.all(Char::isDigit)) return (first.toIntOrNull() ?: return null) to from + 1
        var total = 0
        var found = false
        var i = from
        var lastWasTens = false
        while (i < tokens.size) {
            val w = tokens[i]
            val v = TENS[w] ?: UNITS[w]
            if (v != null && (!found || lastWasTens)) {
                total += v
                lastWasTens = TENS.containsKey(w) && v < 100
                found = true
                i++
            } else if (w == "e" && found && lastWasTens && i + 1 < tokens.size && UNITS.containsKey(tokens[i + 1])) {
                i++ // "vinte e cinco"
            } else {
                break
            }
        }
        return if (found) total to i else null
    }

    /** "5 minutos", "um minuto e trinta segundos", "meia hora", "uma hora e meia" → segundos. */
    internal fun parseDuration(tokens: List<String>): Int {
        var total = 0
        var i = 0
        var lastUnit = 60
        while (i < tokens.size) {
            val w = tokens[i]
            if (w == "meia" && i + 1 < tokens.size && tokens[i + 1].startsWith("hora")) {
                total += 1800; lastUnit = 3600; i += 2; continue
            }
            if (w == "meia" && i > 0 && tokens[i - 1] == "e") {
                total += lastUnit / 2; i++; continue
            }
            val n = readNumber(tokens, i)
            if (n == null) { i++; continue }
            val (value, next) = n
            val unit = tokens.getOrNull(next) ?: ""
            val isUnit = unit.startsWith("segundo") || unit.startsWith("minuto") || unit.startsWith("hora") ||
                unit == "min" || unit == "h"
            // "um timer", "uma contagem": artigo, não número
            if ((w == "um" || w == "uma") && !isUnit) { i++; continue }
            when {
                unit.startsWith("segundo") -> { total += value; lastUnit = 1; i = next + 1 }
                unit.startsWith("minuto") || unit == "min" -> { total += value * 60; lastUnit = 60; i = next + 1 }
                unit.startsWith("hora") || unit == "h" -> { total += value * 3600; lastUnit = 3600; i = next + 1 }
                else -> {
                    // número sem unidade: depois de horas = minutos; depois de minutos = segundos; senão minutos
                    val u = when (lastUnit) { 3600 -> 60; 60 -> if (total > 0) 1 else 60; else -> 1 }
                    total += value * u; i = next
                }
            }
        }
        return total
    }

    /** "às 7", "às sete e meia", "6 e 15 da manhã", "meio-dia" → (hora, minuto). */
    internal fun parseClock(tokens: List<String>): Pair<Int, Int>? {
        val text = " " + tokens.joinToString(" ") + " "
        if (text.contains(" meio dia ") || text.contains(" meiodia ")) return 12 to minutesAfter(tokens, "dia")
        if (text.contains(" meia noite ") || text.contains(" meianoite ")) return 0 to minutesAfter(tokens, "noite")

        // Prefere o número que vem depois de "às"/"pra"/"para"; senão o primeiro que pareça hora
        val afterAs = tokens.indices.firstOrNull { k ->
            k > 0 && tokens[k - 1] in setOf("as", "pras", "pra", "para", "das") && readNumber(tokens, k) != null
        }
        var i = afterAs ?: 0
        while (i < tokens.size) {
            val n = readNumber(tokens, i)
            if (n == null || n.first > 24) { i++; continue }
            val w = tokens[i]
            val nextTok = tokens.getOrNull(n.second)
            if (afterAs == null && (w == "um" || w == "uma") &&
                !(nextTok == null || nextTok.startsWith("hora") || nextTok == "e" || nextTok == "da" || nextTok == "h")) {
                i++; continue
            }
            var hour = n.first
            var j = n.second
            var minute = 0
            if (tokens.getOrNull(j)?.startsWith("hora") == true || tokens.getOrNull(j) == "h") j++
            if (tokens.getOrNull(j) == "e") {
                if (tokens.getOrNull(j + 1) == "meia") { minute = 30; j += 2 }
                else readNumber(tokens, j + 1)?.let { (m, nx) -> if (m < 60) { minute = m; j = nx } }
            } else {
                readNumber(tokens, j)?.let { (m, nx) -> if (m < 60) { minute = m; j = nx } }
            }
            if ((text.contains(" da tarde ") || text.contains(" da noite ")) && hour in 1..11) hour += 12
            if (hour == 24) hour = 0
            return hour to minute
        }
        return null
    }

    private fun minutesAfter(tokens: List<String>, word: String): Int {
        val idx = tokens.indexOf(word)
        if (idx < 0) return 0
        if (tokens.getOrNull(idx + 1) == "e") {
            if (tokens.getOrNull(idx + 2) == "meia") return 30
            readNumber(tokens, idx + 2)?.let { if (it.first < 60) return it.first }
        }
        return 0
    }

    // ------------------------------------------------------------------ correções

    private fun fix(token: String): String {
        ALIASES[token]?.let { return it }
        if (token in VOCAB || token.length < 6 || token.any(Char::isDigit)) return token
        // Uma letra trocada/sobrando/faltando em palavras de comando compridas
        val maxDist = if (token.length >= 8) 2 else 1
        var best: String? = null
        var bestDist = maxDist + 1
        for (v in VOCAB) {
            if (kotlin.math.abs(v.length - token.length) > maxDist || v.length < 6) continue
            val d = distance(token, v, maxDist)
            if (d < bestDist) { bestDist = d; best = v }
        }
        return best ?: token
    }

    /** Distância de edição (Levenshtein), interrompida cedo acima de [limit]. */
    internal fun distance(a: String, b: String, limit: Int): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > limit) return limit + 1
            prev = cur
        }
        return prev[b.length]
    }

    private fun splitGluedWake(original: MutableList<String>, norm: MutableList<String>) {
        var i = 0
        // depois de "ei"/"hey"/"oi"/"ok", o "a" grudado vem na palavra seguinte
        if (norm.size > 1 && norm[0] in setOf("ei", "hey", "hei", "oi", "ok")) i = 1
        val w = norm.getOrNull(i) ?: return
        if (w.length < 4 || w in VOCAB || ALIASES.containsKey(w)) return
        val prefixLen = when {
            w.startsWith("ha") && fix(w.drop(2)) in VOCAB -> 2
            w.startsWith("a") && fix(w.drop(1)) in VOCAB -> 1
            else -> return
        }
        norm[i] = w.drop(prefixLen)
        original[i] = original[i].drop(prefixLen)
        norm.add(i, "a")
        original.add(i, "A")
    }

    fun normalize(s: String): String {
        val lower = s.lowercase(Locale.ROOT)
        val noAccents = Normalizer.normalize(lower, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return noAccents.replace(Regex("[^a-z0-9%]"), "")
    }

    private fun splitPercent(original: MutableList<String>, norm: MutableList<String>) {
        var i = 0
        while (i < norm.size) {
            val n = norm[i]
            if (n.length > 1 && n.endsWith("%")) {
                norm[i] = n.dropLast(1); original[i] = original[i].dropLast(1)
                norm.add(i + 1, "%"); original.add(i + 1, "%")
            } else if (n == "por" && i + 1 < norm.size && norm[i + 1] == "cento") {
                norm[i] = "%"; original[i] = "%"
                norm.removeAt(i + 1); original.removeAt(i + 1)
            }
            i++
        }
    }

    /** "7h30" → "7" "30"; "7h" → "7" "h". */
    private fun splitHours(original: MutableList<String>, norm: MutableList<String>) {
        val re = Regex("^(\\d{1,2})h(\\d{0,2})$")
        var i = 0
        while (i < norm.size) {
            val m = re.matchEntire(norm[i])
            if (m != null) {
                val h = m.groupValues[1]
                val min = m.groupValues[2].ifEmpty { "h" }
                norm[i] = h; original[i] = h
                norm.add(i + 1, min); original.add(i + 1, min)
            }
            i++
        }
    }

    private fun startsWith(tokens: List<String>, from: Int, prefix: List<String>): Boolean {
        if (tokens.size - from < prefix.size) return false
        for (k in prefix.indices) if (tokens[from + k] != prefix[k]) return false
        return true
    }

    /** Verifica se o texto contém alguma das expressões como palavras inteiras. */
    private fun has(text: String, vararg phrases: String): Boolean {
        val padded = " $text "
        return phrases.any { padded.contains(" $it ") }
    }
}
