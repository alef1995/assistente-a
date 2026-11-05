package com.alef.assistentea.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    private fun cmd(s: String) = CommandParser.parse(s).command

    @Test fun tocarArtista() {
        assertEquals(Command.Play("Matuê"), cmd("Alexa, tocar Matuê"))
        assertEquals(Command.Play("Evidências"), cmd("Alexa tocar Evidências."))
        assertEquals(Command.Play("Evidências"), cmd("toca a música Evidências"))
    }

    @Test fun tocarMusicasDoArtista() {
        assertEquals(
            Command.Play("Henrique e Juliano", "Henrique e Juliano"),
            cmd("Alexa, tocar músicas do Henrique e Juliano.")
        )
        assertEquals(
            Command.Play("Marília Mendonça", "Marília Mendonça"),
            cmd("coloca umas músicas da Marília Mendonça no Spotify")
        )
    }

    @Test fun nomeDeMusicaComPalavrasDeComando() {
        assertEquals(Command.Play("Próxima Parada"), cmd("tocar Próxima Parada"))
        assertEquals(Command.Play("Pausa"), cmd("tocar Pausa"))
    }

    @Test fun navegacao() {
        assertEquals(Command.Next, cmd("Alexa, próxima música."))
        assertEquals(Command.Next, cmd("pula essa"))
        assertEquals(Command.Next, cmd("toca a próxima"))
        assertEquals(Command.Previous, cmd("música anterior"))
        assertEquals(Command.Pause, cmd("Alexa, pausar."))
        assertEquals(Command.Resume, cmd("Alexa, continuar."))
        assertEquals(Command.Resume, cmd("despausar"))
        assertEquals(Command.Resume, cmd("toca o som"))
        assertEquals(Command.Stop, cmd("Alexa, parar a música."))
    }

    /** Como os reconhecedores costumam transcrever errado. */
    @Test fun errosDeReconhecimento() {
        assertEquals(Command.Pause, cmd("posar"))
        assertEquals(Command.Next, cmd("a próxima"))
        assertEquals(Command.VolumeUp, cmd("almentar o valume"))
        assertEquals(Command.Previous, cmd("musica anteriro"))
        assertEquals(Command.Play("Evidências"), cmd("torcar Evidências"))
        assertEquals(Command.SetVolume(40), cmd("volume 40"))
    }

    @Test fun nomeAlexa() {
        assertEquals(Command.WakeOnly, cmd("Alexa"))
        assertEquals(Command.WakeOnly, cmd("Alexia"))
        assertEquals(Command.WakeOnly, cmd("a lexa"))
        assertEquals(Command.WakeOnly, cmd("Ei, Alexa"))
        assertEquals(Command.Pause, cmd("Alessa, pausar"))
        assertEquals(Command.Next, cmd("Aleixa próxima"))
        assertEquals(Command.Play("Alcione"), cmd("Alexa, tocar Alcione"))
        assertTrue(CommandParser.parse("Alexa toca Raça Negra").hadWakeWord)
        assertFalse(CommandParser.parse("a gente toca junto").hadWakeWord)
        assertFalse(CommandParser.parse("pausar").hadWakeWord)
        assertFalse(CommandParser.parse("a próxima").hadWakeWord)
        // reconhecedor offline: o nome pode aparecer no meio de pedaços da música
        assertEquals("", CommandParser.findWake("amor alexa"))
        assertEquals("pausar", CommandParser.findWake("alexa pausar"))
        assertEquals("tocar alcione", CommandParser.findWake("e a lexa tocar alcione"))
        assertEquals(null, CommandParser.findWake("a vida é bela"))
        assertEquals(null, CommandParser.findWake("a lisa"))
    }

    @Test fun volume() {
        assertEquals(Command.VolumeUp, cmd("Alexa, aumentar volume."))
        assertEquals(Command.VolumeDown, cmd("Alexa, diminuir volume."))
        assertEquals(Command.VolumeDown, cmd("abaixa o som"))
        assertEquals(Command.VolumeDown, cmd("Alexa, abaixa o som"))
        assertEquals(Command.SetVolume(50), cmd("Alexa, volume 50%."))
        assertEquals(Command.SetVolume(50), cmd("volume cinquenta por cento"))
        assertEquals(Command.SetVolume(35), cmd("coloca o volume em trinta e cinco"))
        assertEquals(Command.SetVolume(100), cmd("volume máximo"))
        assertEquals(Command.VolumeQuery, cmd("qual o volume"))
        assertEquals(Command.Mute, cmd("Alexa, silenciar."))
        assertEquals(Command.Unmute, cmd("tirar do mudo"))
    }

    @Test fun utilidades() {
        assertEquals(Command.Time, cmd("Alexa, que horas são?"))
        assertEquals(Command.Date, cmd("que dia é hoje"))
        assertEquals(Command.Battery, cmd("quanto tem de bateria"))
        assertEquals(Command.Flashlight(true), cmd("Alexa, liga a lanterna"))
        assertEquals(Command.Flashlight(false), cmd("apaga a lanterna"))
        assertEquals(Command.OpenApp("WhatsApp"), cmd("Alexa, abrir o WhatsApp"))
        assertEquals(Command.OpenApp("Waze"), cmd("abre Waze"))
    }

    @Test fun temporizador() {
        assertEquals(Command.Timer(300), cmd("Alexa, timer de 5 minutos"))
        assertEquals(Command.Timer(300), cmd("coloca um timer de cinco minutos"))
        assertEquals(Command.Timer(90), cmd("temporizador de um minuto e trinta segundos"))
        assertEquals(Command.Timer(1800), cmd("timer de meia hora"))
        assertEquals(Command.Timer(5400), cmd("timer de uma hora e meia"))
        assertEquals(Command.Timer(600), cmd("timer de dez"))
        assertEquals(Command.Timer(0), cmd("coloca um timer"))
    }

    @Test fun despertador() {
        assertEquals(Command.Alarm(7, 0), cmd("Alexa, despertador às 7"))
        assertEquals(Command.Alarm(6, 30), cmd("me acorda às seis e meia"))
        assertEquals(Command.Alarm(7, 30), cmd("alarme para 7:30"))
        assertEquals(Command.Alarm(22, 0), cmd("coloca um alarme às 10 da noite"))
        assertEquals(Command.Alarm(13, 15), cmd("alarme pra uma e quinze da tarde"))
        assertEquals(Command.Alarm(6, 45), cmd("despertador 6h45"))
        assertEquals(Command.Alarm(12, 0), cmd("alarme ao meio-dia"))
        assertEquals(Command.Alarm(-1, -1), cmd("coloca um despertador"))
    }

    @Test fun conversa() {
        assertEquals(Command.Joke, cmd("Alexa, conta uma piada"))
        assertEquals(Command.Thanks, cmd("obrigado"))
        assertEquals(Command.Greeting, cmd("Alexa, bom dia"))
        assertEquals(Command.StopListening, cmd("Alexa, pode parar de ouvir"))
        assertEquals(Command.StopListening, cmd("tchau"))
        assertEquals(Command.Cancel, cmd("deixa pra lá"))
        assertEquals(Command.Help, cmd("ajuda"))
    }

    @Test fun conversaComumNaoViraComando() {
        assertTrue(cmd("a gente vai jantar fora hoje") is Command.Unknown)
        assertTrue(cmd("a menina saiu cedo") is Command.Unknown)
    }

    @Test fun melhorAlternativa() {
        val r = CommandParser.parseBest(listOf("blá blá", "pausar"))
        assertEquals(Command.Pause, r.command)
        val w = CommandParser.parseBest(listOf("pausar", "alexa pausar"), preferWake = true)
        assertTrue(w.hadWakeWord)
    }
}
