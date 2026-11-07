# A — assistente de voz para músicas (Android)

Assistente pessoal em português do Brasil que controla músicas e volume por voz, no celular Android, com saída para caixa Bluetooth.

> **Baixar o app:** [AssistenteA.apk (última versão)](https://github.com/alef1995/assistente-a/releases/latest/download/AssistenteA.apk)

---

## O que já funciona

**Música e volume**

| Fale | O que acontece |
|---|---|
| "A, tocar Matuê" · "A, tocar Evidências" | Pede a música ao Spotify / YouTube Music / Deezer, que busca e começa a tocar |
| "A, tocar músicas do Henrique e Juliano" | Pede ao player para tocar o artista |
| "A, próxima" · "música anterior" · "toca a próxima" | Pula / volta |
| "A, pausar" · "A, continuar" · "A, parar a música" | Controla o player que estiver tocando |
| "A, aumentar volume" · "diminuir volume" · "volume 50%" | Muda o volume de mídia (vale para a caixa Bluetooth) |
| "Qual o volume?" · "silenciar" · "tirar do mudo" | Consulta / mudo |
| "Que música é essa?" | Diz o nome da música (precisa do acesso a notificações) |

**Utilidades e conversa**

| Fale | O que acontece |
|---|---|
| "Que horas são?" · "Que dia é hoje?" · "Quanto tem de bateria?" | Responde por voz |
| "Timer de 5 minutos" · "timer de meia hora" | Cria um timer no app Relógio |
| "Despertador às 7" · "me acorda às seis e meia" | Cria um alarme no app Relógio |
| "Liga a lanterna" · "apaga a lanterna" | Lanterna do celular |
| "Abrir WhatsApp" · "abre o Waze" | Abre o app |
| "Conta uma piada" · "obrigado" · "bom dia" | Conversa |
| "Pode parar de ouvir" · "tchau" | Desliga a escuta contínua |
| "Ajuda" | Lista os comandos |

**Música dentro da tela da Zuri:** a Zuri pesquisa no YouTube e toca com o **player oficial do YouTube embutido** num cartão de vídeo no canto da tela — o app do YouTube não abre. Para artista, monta uma fila com os primeiros resultados; vídeos bloqueados fora do YouTube são pulados.

**Atualização sem desinstalar:** a Zuri confere no GitHub se há versão nova e mostra o botão "Atualizar" no alto da tela (ou "Procurar atualização" nas configurações). Ela baixa e o Android só pede para confirmar.

**Plano B (tela da Zuri fechada):** o **YouTube (Morphe)** (`app.morphe.android.youtube`) é o player padrão quando está instalado — toda música pedida vai para ele. No modo tablet, quando a música começa, a tela da Zuri volta para a frente e o YouTube segue tocando em segundo plano. Dá para trocar o player nas configurações.

**Modo tablet (padrão)**

- Tela cheia com relógio, **ondas azuis animadas** que reagem à sua voz e à fala da Zuri, e a música tocando no rodapé.
- Tela sempre ligada, Zuri sempre ouvindo; volta sozinha quando o tablet reinicia ou o app é atualizado (com "exibir sobre outros apps" permitido).
- Configurações na engrenagem do canto superior direito.

**Como chamar a Zuri**

- **Escuta contínua:** diga **"Zuri"** a qualquer momento — **funciona com a tela apagada**. Ela responde "Oi!" / "Oi, boa noite!", você faz o pedido e ela repete antes de executar ("Tocando Alcione"). Sem dizer "Zuri", a música segue normalmente.
- Enquanto espera o nome, ela usa um reconhecedor **offline** (Vosk) que não mexe na música; o Google só é usado para entender o pedido depois do "Oi".
- **Ligar sozinha com a caixa Bluetooth:** conectou a caixa, a A começa a ouvir; desconectou, ela para (poupa bateria).
- **Microfone:** sempre o do celular onde o app está instalado (o da caixa Bluetooth é fraco e derruba a qualidade do som). Deixe o celular perto de onde você fala.
- **Widget** na tela inicial (microfone + "ouvir sempre"), **atalhos** segurando o ícone, e **botão de assistente** (segurar o botão de início, se a A for escolhida como app assistente).

A A entende melhor do que antes: corrige erros comuns do reconhecimento de voz ("Atocar", "Aproxima", "posar", "almentar o valume") e dá dicas ao reconhecedor sobre as palavras de comando (Android 13+).

## Zuri para Windows (PC)

A pasta `zuri-pc/` tem a Zuri como programa para Windows: mesma tela (ondas, relógio, temperatura), mesmo
"Zuri" → "Oi" → pedido, mesma IA grátis, clima, notícias, lembretes, lista de compras, bom dia e música do YouTube
dentro da janela. Instalador: [ZuriSetup.exe](https://github.com/alef1995/assistente-a/releases/download/pc-latest/ZuriSetup.exe).

## Instalar no celular

1. No celular, abra o link **Baixar o app** acima.
2. Toque no arquivo baixado. Se o Android pedir, permita **"Instalar apps desconhecidos"** para o navegador.
3. Abra a **A**, toque no microfone e permita o uso do microfone.
4. (Recomendado) Toque em **"Permitir mostrar a música atual"** e ative a **A** na lista. Isso permite ver o nome da música, controlar o player diretamente e, com o Spotify já aberto, trocar de música sem sair da A.
5. (Recomendado) Toque em **"Permitir que a A ligue sozinha"** e ative "exibir sobre outros apps". Sem isso, ao conectar a caixa aparece uma notificação para você tocar, e abrir apps com a tela apagada pode não funcionar.
6. Permita as **notificações** e, se ligar a opção da caixa, o acesso a **dispositivos próximos** (Bluetooth).

Para atualizar, basta baixar e instalar de novo, por cima.

### Requisitos
- Android 8.0 ou superior.
- App **Google** instalado (é ele que faz o reconhecimento de voz) e internet.
- Um app de música: **Spotify** (recomendado), YouTube Music, Deezer ou Amazon Music.
- Voz em português: *Configurações › Sistema › Idiomas › Saída de texto para fala* → Português (Brasil).

## Como foi feito (arquitetura)

App **Android nativo em Kotlin**, sem bibliotecas externas — só recursos oficiais do Android:

```
MainActivity (tela) ─┐                      ┌─ SpeechListener   reconhecimento de voz pt-BR (+ dicas de palavras)
Widget / atalhos ────┤                      ├─ CommandParser    frase → comando (corrige erros de reconhecimento, testado)
Caixa Bluetooth ─────┼─► AssistantService ──┤  AssistantEngine  ouve → entende → executa → responde
Botão assistente ────┘   (1º plano, tela    ├─ VolumeController / MediaControl / MusicLauncher
                          apagada)          ├─ DeviceActions    hora, data, bateria, timer, alarme, lanterna, abrir apps
                                            ├─ PhoneMic         garante o microfone do celular
                                            └─ Speaker          respostas faladas, com a música abaixando
```

**Por que não usar o YouTube direto (erro 153)?** O player embutido do YouTube não permite reprodução automática nem em segundo plano por apps de terceiros. Em vez disso, a A pede a música ao app de streaming pelo mesmo caminho oficial que o Google Assistente usa.

## Compilação automática

Cada envio para a branch `main` dispara o GitHub Actions (`.github/workflows/build.yml`), que:
1. roda os testes do interpretador de comandos;
2. compila o APK;
3. publica o APK na página **Releases** com o link fixo acima.

Nenhum computador é necessário. O APK é assinado com uma chave fixa de depuração (`keystore/`), para que as atualizações instalem por cima.

## Limitações conhecidas (honestas)

- **Reconhecimento de voz usa o Google** e precisa de internet. Em alguns aparelhos o Android toca um "bip" a cada reinício do microfone na escuta contínua.
- **"A" é uma palavra muito curta** para palavra de ativação: na escuta contínua, a A só age se a frase começar com "A" **e** for um comando reconhecido.
- **Ligar sozinha com a caixa:** desde o Android 11, um app em segundo plano não pode ligar o microfone sozinho. Com "exibir sobre outros apps" permitido, a A abre por um instante uma tela invisível e liga; sem a permissão, aparece uma notificação para tocar.
- **Microfone do celular:** se o app Google estiver configurado para gravar por fones Bluetooth, ele pode tentar usar o microfone da caixa no pedido; desative isso nas configurações de voz do app Google.
- **"Tocar X" abre o app de música** na primeira vez; **Spotify gratuito** pode tocar o artista em modo aleatório.

## Próximas fases

- Detector de palavra de ativação offline (ex.: Vosk ou Porcupine) para gastar menos bateria e dispensar o reconhecedor do Google na espera.
- Escolher a faixa exata via API oficial do Spotify e exibir capa do álbum.
- Clima, lembretes e rotinas ("bom dia" com hora, clima e música).

Detalhes e guia de testes: [docs/TESTES.md](docs/TESTES.md).
