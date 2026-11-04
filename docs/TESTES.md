# Roteiro de testes no celular

Faça na ordem. Anote o número do passo que falhar e a mensagem que aparece em azul/vermelho embaixo do microfone.

## Preparação
1. Conecte a caixa Bluetooth ao celular.
2. Abra o Spotify (ou YouTube Music) uma vez e deixe logado.
3. Abra a A e conceda o microfone.
4. Toque em **"Permitir mostrar a música atual"** → ative **A** → volte.

## Testes da Fase 1
| # | Ação | Esperado |
|---|---|---|
| 1 | Toque no microfone e diga "ajuda" | A fala a lista de comandos pela caixa |
| 2 | "volume 30%" | A barra de volume do Android aparece em ~30% e a A confirma |
| 3 | "aumentar volume" (2×) | Sobe ~10% por vez |
| 4 | "silenciar" → "tirar do mudo" | Mudo liga e desliga |
| 5 | Toque play no Spotify, volte à A, diga "pausar" | Música pausa |
| 6 | "continuar" | Música volta |
| 7 | "próxima música" | Troca de faixa e o nome aparece no cartão |
| 8 | "que música é essa?" | A diz o título e o artista |

## Testes da Fase 2
| # | Ação | Esperado |
|---|---|---|
| 9 | "A, tocar Evidências" | Spotify abre e toca Evidências |
| 10 | Volte à A, "tocar músicas do Henrique e Juliano" | Toca o artista (pode ser sem abrir o Spotify) |
| 11 | Toque em "Player: automático" para escolher outro app e repita o 9 | Toca no app escolhido |

## Escuta contínua
| # | Ação | Esperado |
|---|---|---|
| 12 | Ligue "Escuta contínua", diga "A, pausar" sem tocar na tela | Música pausa |
| 13 | Diga uma frase qualquer sem "A" | A ignora |

## Novidades (versão "mais perto da Alexa")
| # | Ação | Esperado |
|---|---|---|
| 14 | Ligue "Escuta contínua", **apague a tela**, diga "A, pausar" | Música pausa com a tela apagada; notificação "A está ouvindo" |
| 15 | Diga só "A", espere o bipe, diga "próxima" | Bipe, depois troca a música |
| 16 | "A, que horas são?" · "A, timer de 1 minuto" · "A, liga a lanterna" | Responde a hora · timer criado no Relógio · lanterna acende |
| 17 | "A, abrir WhatsApp" | Abre o WhatsApp |
| 18 | Ative "Ligar sozinha com a caixa", desligue e ligue a caixa | Ao conectar, a A começa a ouvir (ou aparece a notificação "toque para ativar") |
| 19 | Desligue a caixa | A notificação "A está ouvindo" some |
| 20 | Adicione o widget "Falar com a A" na tela inicial e toque no microfone | A ouve sem abrir o app |
| 21 | (Caixa com microfone) Ative "Ouvir pelo microfone da caixa", toque no microfone e fale perto da caixa | A entende o comando falado na caixa |
| 22 | "A, pode parar de ouvir" | Escuta contínua desliga |

## Problemas comuns
| Mensagem / sintoma | Solução |
|---|---|
| "Reconhecimento de voz indisponível" | Instale/atualize o app **Google**; em *Configurações › Google › Voz* deixe Português (Brasil) |
| "Sem conexão com a internet…" | O reconhecimento usa internet; verifique Wi-Fi/dados |
| A não fala nada | *Configurações › Saída de texto para fala* → motor Google, idioma Português (Brasil), e baixe a voz |
| Pausar/continuar não funciona | Conceda o "acesso a notificações" (passo 4 da preparação) |
| "Tocar X" abre o Spotify mas não toca | Abra o Spotify uma vez manualmente e toque algo; depois tente de novo |
| Instalação bloqueada | Permita "Instalar apps desconhecidos" para o navegador/arquivos |
| "App não instalado" ao atualizar | Desinstale a versão antiga uma vez e instale a nova |
| Escuta contínua para sozinha depois de um tempo | Em *Configurações › Apps › A › Bateria*, escolha "Sem restrições" |
| Com a tela apagada, "abrir app"/"tocar" não abre nada | Permita "exibir sobre outros apps" para a A |
