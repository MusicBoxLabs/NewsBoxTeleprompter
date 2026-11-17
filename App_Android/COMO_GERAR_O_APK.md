# NEWS BOX TELEPROMPTER — versão de UM PEDAL

## A ordem importa

São dois aparelhos diferentes guardando duas coisas diferentes:

- o **celular** guarda o aplicativo;
- a **caixinha** guarda uma cópia do aplicativo para entregar a quem
  comprar, e guarda os textos.

Gravar a caixinha **não** atualiza o app que já está no celular, e
gerar o app **não** atualiza a caixinha sozinho — a não ser pelo passo
automático que está descrito aqui embaixo.

A ordem certa é sempre esta:

    1. Android Studio  →  Build  →  Build APK(s)
    2. Arduino IDE     →  gravar o ESP32
    3. no celular      →  desinstalar o app antigo e instalar o novo

O passo 3 é o mais esquecido. Enquanto o app velho estiver instalado, é
ele que abre — por mais vezes que a caixinha seja gravada.

---

## 1. Gerar o APK

No Android Studio, abrindo a pasta `UM_PEDAL/APP_NewsBoxTeleprompter`:

    Build → Build Bundle(s) / APK(s) → Build APK(s)

Quando terminar, olhe a aba **Build** do Android Studio. Tem que
aparecer:

    [NEWSBOX] app-debug.apk (NNNNNN bytes) embutido no firmware
    [NEWSBOX] agora grave o ESP32 na IDE do Arduino

Isso quer dizer que o APK recém-gerado já foi convertido em
`UM_PEDAL/05_apk_embutido.h`. Não há mais nada a fazer à mão.

Se aparecer um aviso em vez disso (`firmware NAO atualizado`), o Python
não foi encontrado. Rode você mesmo, uma vez:

    python UM_PEDAL/ferramentas/gerar_apk_embutido.py <caminho do .apk>

---

## 2. Gravar a caixinha

Abra `UM_PEDAL/UM_PEDAL.ino` na IDE do Arduino. Não confunda com o
`NewsTeleprompter.ino` da pasta de cima: aquele é a versão de três
pedais, e continua funcionando como sempre.

> O sketch se chama `UM_PEDAL.ino` porque a IDE do Arduino exige que o
> arquivo tenha o mesmo nome da pasta que o contém. Quando os dois não
> batem, ela se oferece para "arrumar" — e arruma movendo **só o
> `.ino`**, deixando os dez `.h` para trás. O sketch resultante não
> compila, e o erro não diz o motivo. Com os nomes iguais, ela não
> pergunta nada.

Ao ligar, o monitor serial imprime:

    App embutido: NNNNNN bytes

Esse número tem que bater com o do passo 1. Se não bater, a caixinha
está com uma versão antiga do app dentro dela.

---

## 3. Instalar no celular

1. Conecte o celular no wi-fi **NEWS_BOX** (senha `12345678`).
2. Abra o navegador e digite **192.168.4.1**.
3. Toque em **INSTALAR O APLICATIVO**.
4. Se já havia uma versão instalada, **desinstale primeiro**. O Android
   recusa a instalação por cima quando a assinatura é diferente, e a
   mensagem de erro não explica isso.

---

## O que mudou nesta versão

- **Um pedal só.** Pisar e segurar desce o texto; dois toques voltam ao
  começo sem parar a câmera; três toques encerram a gravação. Começar a
  gravação é só pelo botão na tela — de propósito.
- **Sem espelhamento e sem modo só-tela.** Câmera frontal, sempre.
- **Quatro telas**: INÍCIO, PASTA DE NOTÍCIAS, AJUSTES DE TELA e
  LEITURA. A câmera fica ligada por trás de todas.
- **Três tamanhos de letra**, uma velocidade só.
- **A Redação** (escrever e enviar textos) continua no navegador, em
  `192.168.4.1/redacao`, e agora aparece no rodapé em vez de ocupar
  uma linha do menu.
- **O receptor USB-C** é detectado na hora em que for plugado. Não
  precisa mais fechar e abrir o aplicativo.
