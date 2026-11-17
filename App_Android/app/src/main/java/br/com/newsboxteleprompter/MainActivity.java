package br.com.newsboxteleprompter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * NEWS BOX TELEPROMPTER — o app da caixinha BoxTurner.
 *
 * Quatro telas, uma Activity: INICIO, TEXTOS, AJUSTES e LEITURA. A
 * camera frontal fica ligada por tras de todas.
 *
 * A CAIXINHA chega ao celular como teclado (cabo OTG) ou como teclado
 * Bluetooth (BoxTurner). O app nao fala com ela por rede: so recebe as
 * teclas, e elas valem assim:
 *
 *   SUBIR  (botao esquerdo): Page Up, seta esquerda ou seta para cima
 *   DESCER (botao direito):  Page Down, seta direita ou seta para baixo
 *       segurar = rola enquanto a tecla estiver apertada
 *   INICIO (3 toques no esquerdo): Home — volta ao comeco, sem parar a camera
 *   ENCERRAR (3 toques no direito): End — encerra a gravacao
 *
 * Gravar so pelo botao na tela, de proposito: um encerramento acidental
 * da para refazer; um inicio acidental encheria a galeria.
 */
public class MainActivity extends Activity implements PrompterView.Listener {

    private static final int PEDIDO_PERMISSOES = 12;
    private static final int PEDIDO_IMPORTAR = 41;
    private static final int T_INICIO = 0, T_TEXTOS = 1, T_AJUSTES = 2, T_LEITURA = 3;

    /** Velocidade da rolagem em dp. O mesmo ritmo do BoxTurner (44). */
    private static final float VELOCIDADE_DP = 44f;

    private RecordingEngine gravador;

    private TextureView cameraPreview;
    private PrompterView prompter;
    private View telaInicio, telaTextos, telaAjustes, telaLeitura;
    private LinearLayout listaTextos;
    private TextView avisoCaixa, tituloLeitura, restanteLeitura, vaziosTextos;
    private View ledGravacao, botaoRec;
    private TextView rotuloRec;
    private View[] botoesLetra;
    private View[] botoesImagem;

    private int tela = T_INICIO;
    private boolean gravando = false, camPronta = false;
    private int tempoTotalSeg = 0;
    private List<TextosLocais.Texto> itens = new ArrayList<>();

    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle estado) {
        super.onCreate(estado);
        // Prompter que apaga no meio da leitura nao serve para nada.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        prefs = getPreferences(MODE_PRIVATE);

        cameraPreview  = findViewById(R.id.preview);
        prompter       = findViewById(R.id.prompter);
        telaInicio     = findViewById(R.id.telaInicio);
        telaTextos     = findViewById(R.id.telaTextos);
        telaAjustes    = findViewById(R.id.telaAjustes);
        telaLeitura    = findViewById(R.id.telaLeitura);
        listaTextos    = findViewById(R.id.listaTextos);
        vaziosTextos   = findViewById(R.id.vaziosTextos);
        avisoCaixa     = findViewById(R.id.aviso);
        tituloLeitura  = findViewById(R.id.tituloLeitura);
        restanteLeitura= findViewById(R.id.restanteLeitura);
        ledGravacao    = findViewById(R.id.led);
        botaoRec       = findViewById(R.id.botaoRec);
        rotuloRec      = findViewById(R.id.rotuloRec);

        botoesLetra = new View[]{ findViewById(R.id.letraMenor),
                                  findViewById(R.id.letraNormal),
                                  findViewById(R.id.letraMaior) };
        botoesImagem = new View[]{ findViewById(R.id.imagemNormal),
                                   findViewById(R.id.imagemEspelhada) };

        prompter.setListener(this);
        prompter.setVelocidadeDp(VELOCIDADE_DP);
        aplicarTamanhoSalvo();
        mostrarAviso(null);

        findViewById(R.id.itemTextos).setOnClickListener(v -> { irPara(T_TEXTOS); carregarLista(); });
        findViewById(R.id.itemAjustes).setOnClickListener(v -> irPara(T_AJUSTES));
        findViewById(R.id.voltarTextos).setOnClickListener(v -> irPara(T_INICIO));
        findViewById(R.id.voltarAjustes).setOnClickListener(v -> irPara(T_INICIO));
        findViewById(R.id.botaoImportar).setOnClickListener(v -> abrirSeletorDeTextos());

        for (int i = 0; i < botoesLetra.length; i++) {
            final int qual = i;
            botoesLetra[i].setOnClickListener(v -> escolherTamanho(qual));
        }
        botoesImagem[0].setOnClickListener(v -> escolherEspelho(false));
        botoesImagem[1].setOnClickListener(v -> escolherEspelho(true));

        botaoRec.setOnClickListener(v -> alternarGravacao());
        // Os botoes da tela rolam enquanto o dedo estiver neles, como o cabo.
        segurarPara(findViewById(R.id.botaoSubir), -1);
        segurarPara(findViewById(R.id.botaoDescer), 1);

        afastarDasBarrasDoCelular();
        medirQuadroDaLeitura();
        irPara(T_INICIO);

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA,
                                            Manifest.permission.RECORD_AUDIO}, PEDIDO_PERMISSOES);
        } else {
            abrirCamera();
        }
        tratarCompartilhamento(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        tratarCompartilhamento(intent);
    }

    @Override public void onRequestPermissionsResult(int c, String[] p, int[] r) {
        super.onRequestPermissionsResult(c, p, r);
        boolean tudo = r.length > 0;
        for (int g : r) if (g != PackageManager.PERMISSION_GRANTED) tudo = false;
        if (tudo) abrirCamera();
        else mostrarAviso("Sem câmera e microfone o app só mostra o texto, não grava.");
    }

    // ═══════════════════════════════════════════════════════
    //  TECLAS DA CAIXINHA
    // ═══════════════════════════════════════════════════════
    /**
     * Tudo que a caixinha manda chega aqui, pelo cabo ou pelo Bluetooth.
     * So age na tela de LEITURA; nas outras telas o Android segue normal.
     *
     * A repeticao automatica do teclado e ignorada: quem rola e a tecla
     * apertada, nao a repeticao do sistema.
     */
    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (tela != T_LEITURA) return super.dispatchKeyEvent(e);

        int k = e.getKeyCode();
        int dir = direcaoDaTecla(k);
        boolean inicio = (k == KeyEvent.KEYCODE_MOVE_HOME);
        boolean fim = (k == KeyEvent.KEYCODE_MOVE_END);
        if (dir == 0 && !inicio && !fim) return super.dispatchKeyEvent(e);

        if (e.getRepeatCount() > 0) return true;
        int acao = e.getAction();
        if (dir != 0) {
            if (acao == KeyEvent.ACTION_DOWN) prompter.rolar(dir);
            else if (acao == KeyEvent.ACTION_UP) prompter.parar();
        } else if (acao == KeyEvent.ACTION_DOWN) {
            if (inicio) {
                // Volta ao comeco SEM parar a camera: e assim que se refaz
                // uma tomada sem picotar o video.
                prompter.irParaTopo();
                atualizarRestante();
            } else if (gravando) {
                encerrarGravacao();
            }
        }
        return true;
    }

    /** -1 = subir, 1 = descer, 0 = nao e tecla de rolagem. */
    private static int direcaoDaTecla(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_UP:
                return -1;
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                return 1;
            default:
                return 0;
        }
    }

    // ═══════════════════════════════════════════════════════
    //  NAVEGACAO
    // ═══════════════════════════════════════════════════════
    private void irPara(int qual) {
        tela = qual;
        ajustarBrilho();
        telaInicio .setVisibility(qual == T_INICIO  ? View.VISIBLE : View.GONE);
        telaTextos .setVisibility(qual == T_TEXTOS  ? View.VISIBLE : View.GONE);
        telaAjustes.setVisibility(qual == T_AJUSTES ? View.VISIBLE : View.GONE);
        telaLeitura.setVisibility(qual == T_LEITURA ? View.VISIBLE : View.GONE);
        prompter   .setVisibility(qual == T_LEITURA ? View.VISIBLE : View.GONE);
    }

    /**
     * Deixa a barra de navegacao do celular em paz. O app roda em tela
     * cheia, entao o conteudo desce ate a borda de baixo, onde moram os
     * tres botoes do Android. A altura dessa barra muda por aparelho, e
     * so e conhecida depois que a janela aparece: cada tela recebe esse
     * tanto como espaco extra.
     */
    private void afastarDasBarrasDoCelular() {
        final View[] telas = { telaInicio, telaTextos, telaAjustes, telaLeitura, avisoCaixa };
        final int[] baseTopo = new int[telas.length];
        final int[] baseBaixo = new int[telas.length];
        final int[] baseEsq = new int[telas.length];
        final int[] baseDir = new int[telas.length];
        for (int i = 0; i < telas.length; i++) {
            baseTopo[i]  = telas[i].getPaddingTop();
            baseBaixo[i] = telas[i].getPaddingBottom();
            baseEsq[i]   = telas[i].getPaddingLeft();
            baseDir[i]   = telas[i].getPaddingRight();
        }
        // As medidas de base sao lidas UMA vez: somar a cada chamada
        // faria a tela encolher a cada giro do aparelho.
        findViewById(R.id.raiz).setOnApplyWindowInsetsListener((v, recuos) -> {
            int cima  = recuos.getSystemWindowInsetTop();
            int baixo = recuos.getSystemWindowInsetBottom();
            // Com o celular deitado, os botoes do Android vao para a lateral.
            int esq   = recuos.getSystemWindowInsetLeft();
            int dir   = recuos.getSystemWindowInsetRight();
            for (int i = 0; i < telas.length; i++) {
                View t = telas[i];
                // O aviso flutua sobre as outras telas: so precisa fugir
                // da barra de baixo.
                boolean eOAviso = (i == telas.length - 1);
                t.setPadding(baseEsq[i] + esq, baseTopo[i] + (eOAviso ? 0 : cima),
                             baseDir[i] + dir, baseBaixo[i] + baixo);
            }
            return recuos;
        });
    }

    /**
     * Mede onde as duas barras da LEITURA estao e avisa o prompter, para
     * o fim do texto cair dentro da area visivel de verdade.
     */
    private void medirQuadroDaLeitura() {
        final View topo = findViewById(R.id.barraTopo);
        final View rodape = findViewById(R.id.barraControles);
        View.OnLayoutChangeListener ouvinte = (v, a, b, c, d, e, f, g, h) -> {
            int altura = telaLeitura.getHeight();
            if (altura <= 0 || rodape.getHeight() <= 0) return;
            prompter.setMargensDeQuadro(topo.getBottom(), altura - rodape.getTop());
        };
        topo.addOnLayoutChangeListener(ouvinte);
        rodape.addOnLayoutChangeListener(ouvinte);
        telaLeitura.addOnLayoutChangeListener(ouvinte);
    }

    /**
     * A tela nao pode apagar, e nao pode escurecer. O apagamento por
     * tempo limite e resolvido com FLAG_KEEP_SCREEN_ON, no onCreate. O
     * escurecimento e contornado pedindo brilho maximo — so na tela de
     * LEITURA e so no modo espelhado, porque la o vidro devolve ao olho
     * uma fracao da luz.
     */
    private void ajustarBrilho() {
        boolean precisaDeLuz = (tela == T_LEITURA) || prompter.estaEspelhado();
        try {
            WindowManager.LayoutParams p = getWindow().getAttributes();
            p.screenBrightness = precisaDeLuz
                    ? 1.0f
                    : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
            getWindow().setAttributes(p);
        } catch (Exception e) {
            // Aparelho que nao deixa: a tela continua acesa pelo FLAG.
        }
    }

    // ═══════════════════════════════════════════════════════
    //  PASTA DE NOTICIAS (textos guardados no aparelho)
    // ═══════════════════════════════════════════════════════
    private void carregarLista() {
        itens = TextosLocais.listar(this);
        desenharLista();
    }

    private void desenharLista() {
        listaTextos.removeAllViews();
        if (itens.isEmpty()) {
            vaziosTextos.setText("Nenhum texto ainda.\n\nToque em IMPORTAR TEXTO e escolha\narquivos .txt, ou compartilhe um .txt\ncom o NewsBox.");
            vaziosTextos.setVisibility(View.VISIBLE);
            return;
        }
        vaziosTextos.setVisibility(View.GONE);
        LayoutInflater inflador = LayoutInflater.from(this);
        boolean primeiro = true;
        for (final TextosLocais.Texto item : itens) {
            View linha = inflador.inflate(R.layout.item_texto, listaTextos, false);
            // O primeiro da lista vem aceso: e quase sempre o texto que
            // a pessoa acabou de importar.
            if (primeiro) { trocarFundo(linha, R.drawable.fundo_item_destaque); primeiro = false; }
            ((TextView) linha.findViewById(R.id.itemNome)).setText(item.nome);
            ((TextView) linha.findViewById(R.id.itemInfo)).setText(
                    item.palavras + " palavras  ·  " + TextosLocais.mmss(item.tempoSeg) + " de locução");
            linha.setOnClickListener(v -> abrirTexto(item));
            listaTextos.addView(linha);
        }
    }

    private void abrirTexto(final TextosLocais.Texto item) {
        String bruto;
        try {
            bruto = TextosLocais.ler(this, item);
        } catch (Exception e) {
            mostrarAviso("Não consegui abrir o texto: " + e.getMessage());
            return;
        }
        tituloLeitura.setText(item.nome);
        tempoTotalSeg = item.tempoSeg;
        ultimoRestanteEscrito = -1;
        prompter.setTexto(TextoParser.lerTexto(bruto));
        irPara(T_LEITURA);
        atualizarRestante();
    }

    private void abrirSeletorDeTextos() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, PEDIDO_IMPORTAR);
    }

    @Override protected void onActivityResult(int pedido, int resultado, Intent dados) {
        super.onActivityResult(pedido, resultado, dados);
        if (pedido != PEDIDO_IMPORTAR || resultado != RESULT_OK || dados == null) return;
        List<Uri> uris = new ArrayList<>();
        if (dados.getClipData() != null) {
            for (int i = 0; i < dados.getClipData().getItemCount(); i++)
                uris.add(dados.getClipData().getItemAt(i).getUri());
        } else if (dados.getData() != null) {
            uris.add(dados.getData());
        }
        importarUris(uris);
    }

    private void importarUris(List<Uri> uris) {
        int ok = 0;
        String falha = null;
        for (Uri u : uris) {
            try {
                TextosLocais.importar(this, u);
                ok++;
            } catch (Exception e) {
                falha = e.getMessage();
            }
        }
        if (ok > 0) mostrarAviso(ok + (ok == 1 ? " texto importado" : " textos importados"));
        else mostrarAviso("Não consegui importar: " + (falha == null ? "arquivo vazio" : falha));
        if (tela == T_TEXTOS) carregarLista();
    }

    /** Compartilhar > NewsBox: o texto chega direto a pasta de noticias. */
    private void tratarCompartilhamento(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        if (!"text/plain".equals(i.getType())) return;
        List<Uri> uris = new ArrayList<>();
        Uri arquivo = (Uri) i.getParcelableExtra(Intent.EXTRA_STREAM);
        if (arquivo != null) {
            uris.add(arquivo);
            importarUris(uris);
        } else {
            CharSequence texto = i.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (texto != null) {
                try {
                    TextosLocais.importarTexto(this, texto.toString());
                    mostrarAviso("1 texto importado");
                } catch (Exception e) {
                    mostrarAviso("Não consegui importar: " + e.getMessage());
                }
            }
        }
        irPara(T_TEXTOS);
        carregarLista();
    }

    // ═══════════════════════════════════════════════════════
    //  AJUSTES
    // ═══════════════════════════════════════════════════════
    private void aplicarTamanhoSalvo() {
        escolherTamanho(prefs.getInt("letra", PrompterView.LETRA_NORMAL));
        escolherEspelho(prefs.getBoolean("espelho", false));
    }

    /**
     * Liga e desliga o modo de teleprompter de vidro. O texto inverte, e
     * a camera some da tela: a lente da base so veria o preto do fundo.
     */
    private void escolherEspelho(boolean espelhar) {
        prompter.setEspelhado(espelhar);
        prompter.setFundoEscuro(espelhar);
        prefs.edit().putBoolean("espelho", espelhar).apply();
        ajustarBrilho();
        for (int i = 0; i < botoesImagem.length; i++) {
            boolean escolhido = (i == 1) == espelhar;
            botoesImagem[i].setSelected(escolhido);
            pintarFilhos(botoesImagem[i], escolhido ? 0xFFF5E642 : 0xFF7A7460);
        }
    }

    private void escolherTamanho(int qual) {
        prompter.setTamanhoLetra(qual);
        prefs.edit().putInt("letra", qual).apply();
        for (int i = 0; i < botoesLetra.length; i++) {
            boolean escolhido = (i == qual);
            botoesLetra[i].setSelected(escolhido);
            pintarFilhos(botoesLetra[i], escolhido ? 0xFFF5E642 : 0xFF7A7460);
        }
    }

    private void pintarFilhos(View caixa, int cor) {
        if (!(caixa instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup g = (android.view.ViewGroup) caixa;
        for (int i = 0; i < g.getChildCount(); i++) {
            View f = g.getChildAt(i);
            if (f instanceof TextView) ((TextView) f).setTextColor(cor);
        }
    }

    /**
     * Trocar o fundo de uma View APAGA o espacamento interno dela; por
     * isso guardamos e devolvemos.
     */
    private void trocarFundo(View v, int desenho) {
        int e = v.getPaddingLeft(),  c = v.getPaddingTop();
        int d = v.getPaddingRight(), b = v.getPaddingBottom();
        v.setBackgroundResource(desenho);
        v.setPadding(e, c, d, b);
    }

    // ═══════════════════════════════════════════════════════
    //  ROLAGEM PELA TELA
    // ═══════════════════════════════════════════════════════
    @SuppressWarnings("ClickableViewAccessibility")
    private void segurarPara(View botao, final int direcao) {
        botao.setOnTouchListener((v, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                v.setPressed(true);
                prompter.rolar(direcao);
                return true;
            }
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                v.setPressed(false);
                prompter.parar();
                return true;
            }
            return false;
        });
    }

    // ═══════════════════════════════════════════════════════
    //  GRAVACAO
    // ═══════════════════════════════════════════════════════
    private void alternarGravacao() {
        if (gravador == null || !camPronta) {
            mostrarAviso("A câmera ainda está abrindo. Um instante.");
            return;
        }
        if (gravando) encerrarGravacao();
        else {
            gravador.start();
            gravando = true;
            pintarGravacao();
        }
    }

    private void encerrarGravacao() {
        if (gravador != null && gravador.isRecording()) gravador.stop();
        gravando = false;
        pintarGravacao();
    }

    private void pintarGravacao() {
        ledGravacao.setVisibility(gravando ? View.VISIBLE : View.INVISIBLE);
        botaoRec.setSelected(gravando);
        rotuloRec.setText(gravando ? "PARAR" : "GRAVAR");
    }

    // ═══════════════════════════════════════════════════════
    //  PROMPTER
    // ═══════════════════════════════════════════════════════
    @Override public void aoParar(boolean noFim) { atualizarRestante(); }
    @Override public void aoProgredir(int pct)    { atualizarRestante(); }

    // O motor chama isto a cada quadro. Reescrever o rodape 60 vezes por
    // segundo roubaria tempo da rolagem, entao so escrevemos quando o
    // numero muda de verdade.
    private int ultimoRestanteEscrito = -1;

    private void atualizarRestante() {
        int restam = prompter.segundosRestantes();
        if (restam == ultimoRestanteEscrito) return;
        ultimoRestanteEscrito = restam;
        restanteLeitura.setText("restam " + TextosLocais.mmss(restam));
    }

    // ═══════════════════════════════════════════════════════
    //  CAMERA
    // ═══════════════════════════════════════════════════════
    private void abrirCamera() {
        gravador = new RecordingEngine(this, cameraPreview, new RecordingEngine.Listener() {
            @Override public void status(String s) { }
            @Override public void input(String s) { }
            @Override public void ready() { runOnUiThread(() -> camPronta = true); }
            @Override public void recordingStarted() { }
            @Override public void finished(final String onde) {
                runOnUiThread(() -> {
                    camPronta = false;
                    gravando = false;
                    pintarGravacao();
                    mostrarAviso("Vídeo salvo em " + onde);
                });
            }
            @Override public void error(final String msg) {
                runOnUiThread(() -> {
                    gravando = false;
                    pintarGravacao();
                    mostrarAviso("Gravação: " + msg);
                });
            }
            @Override public void aviso(final String msg) {
                runOnUiThread(() -> mostrarAviso(msg));
            }
        });
        gravador.openPreview();
        vigiarGiroDaTela();
    }

    /** A previa da camera acompanha o giro do aparelho. */
    private android.hardware.display.DisplayManager.DisplayListener vigiaTela;

    private void vigiarGiroDaTela() {
        cameraPreview.addOnLayoutChangeListener((v, a, b, c, d, e, f, g, h) -> {
            if (gravador != null) gravador.ajustarPrevia();
        });
        android.hardware.display.DisplayManager dm =
                (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (dm == null) return;
        vigiaTela = new android.hardware.display.DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) { }
            @Override public void onDisplayRemoved(int id) { }
            @Override public void onDisplayChanged(int id) {
                if (gravador != null) gravador.ajustarPrevia();
            }
        };
        dm.registerDisplayListener(vigiaTela, new android.os.Handler(getMainLooper()));
    }

    // Recado some sozinho depois de 6 segundos.
    private final Runnable sumirAviso = new Runnable() {
        @Override public void run() { avisoCaixa.setVisibility(View.GONE); }
    };

    private void mostrarAviso(String texto) {
        avisoCaixa.removeCallbacks(sumirAviso);
        if (texto == null || texto.length() == 0) {
            avisoCaixa.setVisibility(View.GONE);
        } else {
            avisoCaixa.setText(texto);
            avisoCaixa.setVisibility(View.VISIBLE);
            avisoCaixa.postDelayed(sumirAviso, 6000);
        }
    }

    @Override public void onBackPressed() {
        if (tela == T_LEITURA) { prompter.parar(); irPara(T_TEXTOS); }
        else if (tela != T_INICIO) irPara(T_INICIO);
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (vigiaTela != null) {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE);
            if (dm != null) dm.unregisterDisplayListener(vigiaTela);
        }
        if (gravador != null) gravador.close();
        super.onDestroy();
    }
}
