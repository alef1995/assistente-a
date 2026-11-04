package com.alef.assistentea.command

/** Todos os comandos que a assistente entende. */
sealed class Command {
    /** "tocar Evidências" → query="Evidências", artist=null
     *  "tocar músicas do Henrique e Juliano" → query="Henrique e Juliano", artist="Henrique e Juliano" */
    data class Play(val query: String, val artist: String? = null) : Command()

    data object Pause : Command()
    data object Resume : Command()
    data object Stop : Command()
    data object Next : Command()
    data object Previous : Command()

    data object VolumeUp : Command()
    data object VolumeDown : Command()
    data class SetVolume(val percent: Int) : Command()
    data object Mute : Command()
    data object Unmute : Command()
    data object VolumeQuery : Command()

    data object NowPlaying : Command()

    // ---- Utilidades
    data object Time : Command()
    data object Date : Command()
    data object Battery : Command()
    /** Temporizador em segundos ("timer de 5 minutos"). */
    data class Timer(val seconds: Int) : Command()
    /** Despertador ("me acorda às 6 e meia"). */
    data class Alarm(val hour: Int, val minute: Int) : Command()
    /** Lanterna: true = ligar, false = desligar. */
    data class Flashlight(val on: Boolean) : Command()
    /** "abrir WhatsApp" → name = "WhatsApp" */
    data class OpenApp(val name: String) : Command()

    // ---- Conversa
    data object Joke : Command()
    data object Thanks : Command()
    data object Greeting : Command()
    data object Help : Command()

    /** "pode parar de ouvir", "tchau": desliga a escuta contínua. */
    data object StopListening : Command()
    /** "cancela", "deixa pra lá": não faz nada. */
    data object Cancel : Command()

    /** Só a palavra de ativação ("A") foi dita. */
    data object WakeOnly : Command()

    data class Unknown(val text: String) : Command()
}

/** Resultado da interpretação de uma frase. */
data class ParseResult(
    val command: Command,
    /** true se a frase começou com a palavra de ativação ("A, ..."). */
    val hadWakeWord: Boolean
)
