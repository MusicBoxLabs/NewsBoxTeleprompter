plugins {
    id("com.android.application")
}

android {
    namespace = "br.com.newsboxteleprompter"
    compileSdk = 36

    defaultConfig {
        applicationId = "br.com.newsboxteleprompter"
        // 29 = Android 10. Abaixo disso o MediaStore nao tem
        // RELATIVE_PATH e o video nao apareceria sozinho na Galeria.
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        // 1.1: app liberado para girar (em pe e deitado); previa da
        // camera e margens das barras do Android acompanham o giro.
        versionName = "1.1"
    }

    // Ha acento em literal Java e em quase todo texto de tela. Fixar
    // UTF-8 evita que a codificacao da maquina que compila mude o
    // resultado — o tipo de bug que so aparece no PC de outra pessoa.
    compileOptions {
        encoding = "UTF-8"
    }

    // NENHUMA dependencia externa, de proposito: o APK precisa caber
    // no flash do proprio ESP32, que e quem entrega o app ao comprador.
}

// ═══════════════════════════════════════════════════════════
//  O APK NAO VAI MAIS PARA O FIRMWARE
// ═══════════════════════════════════════════════════════════
//  O pen drive do instalador agora le o APK da pasta data/ do
//  firmware, enviada com "ESP32 Sketch Data Upload". Copie o APK
//  gerado para: NewsBoxTeleprompter/data/NewsBoxTeleprompter.apk
