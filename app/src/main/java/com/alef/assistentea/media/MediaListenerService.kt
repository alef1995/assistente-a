package com.alef.assistentea.media

import android.service.notification.NotificationListenerService

/**
 * Serviço vazio, exigido pelo Android para conceder "Acesso a notificações".
 * Com esse acesso, o app consegue ver qual música está tocando em qualquer player
 * (Spotify, YouTube Music, Deezer…) e controlá-lo diretamente.
 * O app não lê nem guarda o conteúdo das suas notificações.
 */
class MediaListenerService : NotificationListenerService()
