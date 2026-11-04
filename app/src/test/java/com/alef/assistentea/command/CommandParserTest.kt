package com.alef.assistentea.command

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandParserTest {

    private fun cmd(s: String) = CommandParser.parse(s).command

    @Test fun tocarArtista() {
        assertEquals(Command.Play("Matuê"), cmd("A, tocar Matuê"))
        assertEquals(Command.Play("Evidências"), cmd("A tocar Evidências."))
        assertEquals(Command.Play("Evidências"), cmd("toca a música Evidências"))
    }

    @Test fun tocarMusicasDoArtista() {
        assertEquals(
            Command.Play("Henrique e Juliano", "Henrique e Juliano"),
            cmd("A, tocar músicas do Henrique e Juliano.")
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
        assertEquals(Command.Next, cmd("A, próxima música."))
        assertEquals(Command.Next, cmd("pula essa"))
        assertEquals(Command.Next, cmd("toca a próxima"))
        assertEquals(Command.Previous, cmd("música anterior"))
        assertEquals(Command.Pause, cmd("A, pausar."))
        assertEquals(Command.Resume, cmd("A, continuar."))
        assertEquals(Command.Resume, cmd("despausar"))
        assertEquals(Command.Resume, cmd("toca o som"))
        assertEquals(Command.Stop, cmd("A, parar a música."))
    }

    /** Como o reconhecedor do Google costuma transcrever errado. */
    @Test fun errosDeReconhecimento() {
        assertEquals(Command.Play("Matuê"), cmd("Atocar Matuê"))
        assertTrue(CommandParser.parse("Atocar Matuê").hadWakeWord)
        assertEquals(Command.Pause, cmd("Apausar"))
        assertEquals(Command.Next, cmd("Aproxima"))
        assertEquals(Command.Next, cmd("a próxima"))
        assertEquals(Command.Pause, cmd("Há pausar"))
        assertEquals(Command.Pause, cmd("Ah, posar"))
        assertEquals(Command.Pause, cmd("à pausa"))
        assertEquals(Command.Resume, cmd("Ei A, continuar"))
        assertEquals(Command.Resume, cmd("ei acontinuar"))
        assertEquals(Command.VolumeUp, cmd("A, almentar o valume"))
        assertEquals(Command.SetVolume(40), cmd("a volume 40"))
        assertEquals(Command.Previous, cmd("musica anteriro"))
        assertEquals(Command.Play("Evidências"), cmd("A torcar Evidências"))
    }

    @Test fun volume() {
        assertEquals(Command.VolumeUp, cmd("A, aumentar volume."))
        assertEquals(Command.VolumeDown, cmd("A, diminuir volume."))
        assertEquals(Command.VolumeDown, cmd("abaixa o som"))
        assertEquals(Command.VolumeDown, cmd("A, abaixa o som"))
        assertEquals(Command.SetVolume(50), cmd("A, volume 50%."))
        assertEquals(Command.SetVolume(50), cmd("volume cinquenta por cento"))
        assertEquals(Command.SetVolume(35), cmd("coloca o volume em trinta e cinco"))
        assertEquals(Command.SetVolume(100), cmd("volume máximo"))
        assertEquals(Command.VolumeQuery, cmd("qual o volume"))
        assertEquals(Command.Mute, cmd("A, silenciar."))
        assertEquals(Command.Unmute, cmd("tirar do mudo"))
    }

    @Test fun utilidades() {
        assertEquals(Command.Time, cmd("A, que horas são?"))
        assertEquals(Command.Date, cmd("que dia é hoje"))
        assertEquals(Command.Battery, cmd("quanto tem de bateria"))
        assertEquals(Command.Flashlight(true), cmd("A, liga a lanterna"))
        assertEquals(Command.Flashlight(false), cmd("apaga a lanterna"))
        assertEquals(Command.OpenApp("WhatsApp"), cmd("A, abrir o WhatsApp"))
        assertEquals(Command.OpenApp("Waze"), cmd("abre Waze"))
    }

    @Test fun temporizador() {
        assertEquals(Command.Timer(300), cmd("A, timer de 5 minutos"))
        assertEquals(Command.Timer(300), cmd("coloca um timer de cinco minutos"))
        assertEquals(Command.Timer(90), cmd("temporizador de um minuto e trinta segundos"))
        assertEquals(Command.Timer(1800), cmd("timer de meia hora"))
        assertEquals(Command.Timer(5400), cmd("timer de uma hora e meia"))
        assertEquals(Command.Timer(600), cmd("timer de dez"))
        assertEquals(Command.Timer(0), cmd("coloca um timer"))
    }

    @Test fun despertador() {
        assertEquals(Command.Alarm(7, 0), cmd("A, despertador às 7"))
        assertEquals(Command.Alarm(6, 30), cmd("me acorda às seis e meia"))
        assertEquals(Command.Alarm(7, 30), cmd("alarme para 7:30"))
        assertEquals(Command.Alarm(22, 0), cmd("coloca um alarme às 10 da noite"))
        assertEquals(Command.Alarm(13, 15), cmd("alarme pra uma e quinze da tarde"))
        assertEquals(Command.Alarm(6, 45), cmd("despertador 6h45"))
        assertEquals(Command.Alarm(12, 0), cmd("alarme ao meio-dia"))
        assertEquals(Command.Alarm(-1, -1), cmd("coloca um despertador"))
    }

    @Test fun conversa() {
        assertEquals(Command.Joke, cmd("A, conta uma piada"))
        assertEquals(Command.Thanks, cmd("obrigado"))
        assertEquals(Command.Greeting, cmd("A, bom dia"))
        assertEquals(Command.StopListening, cmd("A, pode parar de ouvir"))
        assertEquals(Command.StopListening, cmd("tchau"))
        assertEquals(Command.Cancel, cmd("deixa pra lá"))
        assertEquals(Command.Help, cmd("ajuda"))
    }

    @Test fun palavraDeAtivacao() {
        assertEquals(Command.WakeOnly, cmd("A"))
        assertEquals(Command.WakeOnly, cmd("Ei, A"))
        assertTrue(CommandParser.parse("A, pausar").hadWakeWord)
        assertFalse(CommandParser.parse("pausar").hadWakeWord)
    }

    @Test fun conversaComumNaoViraComando() {
        assertTrue(cmd("a gente vai jantar fora hoje") is Command.Unknown)
        assertTrue(cmd("a menina saiu cedo") is Command.Unknown)
    }

    @Test fun melhorAlternativa() {
        val r = CommandParser.parseBest(listOf("blá blá", "a pausar"))
        assertEquals(Command.Pause, r.command)
        val w = CommandParser.parseBest(listOf("pausar", "a pausar"), preferWake = true)
        assertTrue(w.hadWakeWord)
    }
}
