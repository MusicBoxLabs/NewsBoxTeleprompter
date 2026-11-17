package br.com.newsboxteleprompter;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * O texto rolando por cima da imagem da camera frontal.
 *
 * Esta View NAO decide nada sozinha. Ela nao sabe o que e um toque
 * duplo, nao conta pedaladas e nao escolhe quando comecar. Ela so
 * desenha o texto na posicao que mandarem e anda na velocidade que
 * mandarem. Quem manda e o ESP32 — o mesmo firmware que ja comanda a
 * pagina web. Foi assim de proposito: com uma cabeca so pensando, o
 * pedal e o dedo nunca discordam, e um bug de gesto se conserta num
 * lugar so.
 *
 * As regras de leitura sao as mesmas da pagina web, porque sao as que
 * funcionam num prompter de verdade:
 *
 *   - a primeira linha NASCE na faixa-guia (42% da altura), nao no
 *     topo, porque e na faixa que o olho fica cravado;
 *   - a ultima linha com conteudo SOBE ate a faixa e para ali. Se ela
 *     parasse na borda de baixo, o locutor teria que baixar o olho
 *     justamente no fecho, depois de ler a pauta inteira no meio da
 *     tela;
 *   - o espaco em branco embaixo do texto e calculado em pixels na
 *     hora, e nao chutado, senao a conta acima nao fecha.
 */
public class PrompterView extends View {

    public interface Listener {
        /** A rolagem bateu no fim (ou voltou ao comeco). */
        void aoParar(boolean noFim);
        /** Andou: 0 a 100. Serve para o rodape do tempo restante. */
        void aoProgredir(int pct);
    }

    // ── Cores, iguais as da pagina web ───────────────────────
    private static final int COR_LEITURA  = 0xFFF5E642;
    private static final int COR_RETRANCA = 0xFFFFFFFF;
    private static final int COR_CUE      = 0xFFFF9F1C;
    private static final int COR_DEIXA    = 0xFF49BFFF;
    private static final int COR_NOTA     = 0xFF7A7460;
    private static final int COR_FECHO    = 0xFF2196FF;

    /** Altura da faixa-guia, em fracao da altura da tela. */
    private static final float FAIXA = 0.42f;

    private final TextPaint tinta = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pincel = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<TextoParser.Linha> linhas = new ArrayList<>();
    private final List<StaticLayout> blocos = new ArrayList<>();
    private final List<Integer> topos = new ArrayList<>();
    private int indiceFecho = -1;

    /** Os tres tamanhos de AJUSTES DE TELA. */
    public static final int LETRA_MENOR = 0, LETRA_NORMAL = 1, LETRA_MAIOR = 2;
    private static final float[] FATOR = { 0.82f, 1.00f, 1.22f };

    private float fonteFator = 1.0f;
    private boolean fundoEscuro = true;      // false quando a camera aparece
    private float alturaTexto = 0;

    private float rolagem = 0;
    private float rolagemMax = 0;
    private int faixaY = 0;
    private int margemTopo = 0, margemRodape = 0;

    private boolean rolando = false;
    private float velPx = 44;
    private int direcao = 1;
    private long ultimoQuadro = 0;
    private Listener listener;

    private final Runnable motor = new Runnable() {
        @Override public void run() {
            if (!rolando) return;
            long agora = System.nanoTime();
            float dt = ultimoQuadro == 0 ? 0 : (agora - ultimoQuadro) / 1_000_000_000f;
            ultimoQuadro = agora;
            if (dt > 0.1f) dt = 0.1f;          // voltou de um engasgo: nao pula meia pauta

            rolagem += velPx * dt * direcao;

            if (direcao > 0 && rolagem >= rolagemMax) {
                rolagem = rolagemMax;
                pararMotor();
                if (listener != null) listener.aoParar(true);
            } else if (direcao < 0 && rolagem <= 0) {
                rolagem = 0;
                pararMotor();
                if (listener != null) listener.aoParar(false);
            } else {
                postDelayed(this, 16);
            }
            if (listener != null) listener.aoProgredir(pct());
            invalidate();
        }
    };

    public PrompterView(Context c) { super(c); iniciar(); }
    public PrompterView(Context c, AttributeSet a) { super(c, a); iniciar(); }

    private void iniciar() {
        tinta.setTypeface(android.graphics.Typeface.MONOSPACE);
        setLayerType(LAYER_TYPE_HARDWARE, null);
    }

    public void setListener(Listener l) { this.listener = l; }

    // ── Conteudo ─────────────────────────────────────────────
    public void setTexto(List<TextoParser.Linha> novas) {
        this.linhas = novas != null ? novas : new ArrayList<TextoParser.Linha>();
        this.indiceFecho = TextoParser.indiceDoFecho(this.linhas);
        this.rolagem = 0;
        remontar();
        invalidate();
    }

    public void setTamanhoLetra(int qual) {
        if (qual < 0 || qual >= FATOR.length) qual = LETRA_NORMAL;
        setFonte(FATOR[qual]);
    }

    public void setFonte(float fator) {
        if (Math.abs(fator - fonteFator) < 0.001f) return;
        // Guarda a linha que esta na faixa e recoloca a MESMA linha la
        // depois de remontar. Sem isso, mudar o corpo da letra no meio
        // da leitura joga o locutor num paragrafo qualquer.
        int ancora = linhaNaFaixa();
        fonteFator = fator;
        remontar();
        irParaLinha(ancora);
        invalidate();
    }


    /** Fundo preto (so texto) ou translucido (a camera aparece atras). */
    public void setFundoEscuro(boolean escuro) { fundoEscuro = escuro; invalidate(); }

    // ── Espelho ──────────────────────────────────────────────
    //
    // Deitado na base de um teleprompter de estudio, o aparelho projeta
    // a tela num vidro inclinado a 45 graus. O vidro devolve a imagem
    // invertida — entao o texto tem que sair invertido para chegar
    // certo ao olho de quem le.
    //
    // Este modo tinha sido cortado do projeto de um pedal junto com a
    // camera traseira, e o corte estava errado. A camera frontal, ali
    // dentro, so ve o preto do fundo e do teto; ela nao serve para
    // nada, mas a TELA serve para tudo. Com o espelho de volta, a mesma
    // caixinha atende quem grava sozinho no celular e quem tem um
    // prompter de jornal na redacao.
    private boolean espelhado = false;

    public void setEspelhado(boolean sim) {
        if (sim == espelhado) return;
        espelhado = sim;
        invalidate();
    }

    public boolean estaEspelhado() { return espelhado; }

    /**
     * Quanto da tela esta coberto pela barra de cima e pela de baixo.
     *
     * Esta View ocupa a tela INTEIRA — inclusive o pedaco que fica
     * atras da barra do titulo e atras dos botoes. Se ela nao souber
     * disso, dois erros aparecem juntos: a faixa-guia sai do lugar, e
     * o fim do texto para escondido atras dos botoes. Foi exatamente
     * esse o defeito que apareceu no primeiro teste de campo.
     */
    public void setMargensDeQuadro(int topo, int rodape) {
        if (topo == margemTopo && rodape == margemRodape) return;
        margemTopo = topo;
        margemRodape = rodape;
        int ancora = linhaNaFaixa();
        remontar();
        irParaLinha(ancora);
        invalidate();
    }

    /** Altura que sobra entre as duas barras: e nela que se le. */
    private int alturaUtil() {
        return Math.max(1, getHeight() - margemTopo - margemRodape);
    }

    // ── Comandos ─────────────────────────────────────────────
    //
    // Nao ha escolha de velocidade na tela: e uma so. Tirar esse ajuste
    // foi de proposito — quem esta com o pe no pedal e o olho na faixa
    // nao vai mexer num controle de velocidade.
    //
    // Mas "uma so" tem que querer dizer uma so DE VERDADE, e por muito
    // tempo nao queria. A velocidade era em pixels por segundo, e pixel
    // nao e palavra: numa tela maior, ou com a letra maior, a mesma
    // frase ocupa mais pixels e leva mais tempo para passar. Medido,
    // isso dava de 100 a 183 palavras por minuto conforme o aparelho —
    // tres velocidades diferentes se passando por uma.
    //
    // A regua agora e o TEMPO. A caixinha, que ja conta as palavras de
    // cada texto, diz quantos segundos aquela locucao deve durar; a
    // View mede a distancia que a rolagem tem para percorrer e divide
    // uma coisa pela outra. O texto passa a durar o que foi anunciado,
    // em qualquer tela e em qualquer corpo de letra — e o "restam 1:24"
    // do rodape deixa de ser estimativa e vira o relogio da rolagem.
    //
    // Nao ha escolha de velocidade na tela: e uma so. Tirar esse ajuste
    // foi de proposito — quem esta com o pe no pedal e o olho na faixa
    // nao vai mexer num controle de velocidade.
    //
    // E ela e FIXA em pixels por segundo: a mesma no texto curto e no
    // comprido, com a letra menor e com a maior.
    //
    // Houve uma versao que calculava a velocidade a partir do tempo de
    // locucao do texto, para que cada texto durasse exatamente o que a
    // lista anunciava. No papel era mais correto. Na pratica era pior,
    // e a razao e boa: um prompter e um instrumento. O pe aprende
    // quanto tempo o texto leva para andar uma linha, e passa a
    // antecipar. Se o ritmo mudar a cada texto, essa memoria nunca se
    // forma — o locutor gasta a gravacao inteira corrigindo o pe em vez
    // de ler.
    //
    // A unidade e o pixel de CSS (o "dp"), nunca o pixel fisico. Sao os
    // mesmos 44 do teleprompter de tres pedais, que ja tem
    // quilometragem. Em pixel fisico, 44 num celular comum valeria 17 —
    // quase tres vezes mais devagar. Foi esse o defeito do primeiro
    // teste de campo.
    private float velocidadeDp = 44f;

    /** Pixels de CSS por segundo. Quem manda o numero e a caixinha. */
    public void setVelocidadeDp(float dp) {
        if (dp >= 8f && dp <= 400f) velocidadeDp = dp;
    }

    private float velocidadeAtual() {
        return velocidadeDp * densidade();
    }

    /**
     * Quanto falta, em segundos, no ritmo em que o texto anda de fato.
     *
     * Sai daqui, e nao de uma regra de tres sobre o tempo de locucao,
     * porque quem sabe a distancia que ainda ha para percorrer e esta
     * View. Com a velocidade fixa, os dois numeros nao batem — e o que
     * o rodape tem que mostrar e o da rolagem, que e o que vai
     * acontecer na tela.
     */
    public int segundosRestantes() {
        float v = velocidadeAtual();
        if (v <= 0) return 0;
        return Math.round(Math.max(0, rolagemMax - rolagem) / v);
    }

    /** Rola no ritmo unico. direcao 1 desce, -1 sobe. */
    public void rolar(int dir) {
        aplicarMotor(true, velocidadeAtual(), dir >= 0 ? 1 : -1);
    }

    /** Para onde estiver. O pe saiu do pedal, ou o dedo do botao. */
    public void parar() {
        pararMotor();
    }

    public void aplicarMotor(boolean deveRolar, float velocidadePx, int dir) {
        this.velPx = velocidadePx;
        this.direcao = dir;
        if (deveRolar) {
            if (!rolando) {
                rolando = true;
                ultimoQuadro = 0;
                removeCallbacks(motor);
                post(motor);
            }
        } else {
            pararMotor();
        }
    }

    private void pararMotor() {
        rolando = false;
        ultimoQuadro = 0;
        removeCallbacks(motor);
    }

    public void irParaTopo() {
        pararMotor();
        rolagem = 0;
        if (listener != null) listener.aoProgredir(0);
        invalidate();
    }

    public boolean estaRolando() { return rolando; }

    public int pct() {
        if (rolagemMax <= 0) return 0;
        int p = Math.round(rolagem / rolagemMax * 100f);
        return Math.max(0, Math.min(100, p));
    }

    // ── Medida e montagem ────────────────────────────────────
    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        int ancora = linhaNaFaixa();
        remontar();
        irParaLinha(ancora);
    }

    /**
     * Base do corpo da letra, pela largura da tela.
     *
     * A conta e a mesma da pagina web — mas a unidade NAO era, e essa
     * diferenca de uma palavra deixou a letra do app pequena demais
     * durante todo este tempo.
     *
     * No navegador, "largura" sai em pixels de CSS: uns 412 num celular
     * comum. A conta da 412 x 0,072 = 29,7, e o teto de 56 nem chega
     * perto. Numa View do Android, getWidth() sai em pixels FISICOS —
     * 1080 no mesmo aparelho. A mesma conta da 77,8, bate no teto de 56
     * e para ali. E 56 pixels fisicos, com a densidade 2,6 desse
     * celular, valem 21 pixels de CSS: a letra do app nascia com 2/3
     * do tamanho da letra do navegador, e o texto aparecia amontoado.
     *
     * Pior: como a rolagem era medida em pixels por segundo, a letra
     * menor tambem fazia o texto andar mais devagar. Um erro de unidade
     * so, dois sintomas que pareciam nao ter relacao nenhuma.
     *
     * Agora a conta e feita em pixels de CSS (o "dp" do Android, que e
     * a mesma coisa) e so no fim vira pixel fisico. O texto sai do
     * mesmo tamanho no navegador e no aplicativo, em qualquer aparelho.
     */
    private float corpoBase() {
        float d = densidade();
        float larguraDp = (getWidth() > 0 ? getWidth() : Math.round(412 * d)) / d;
        float baseDp = Math.min(Math.max(larguraDp * 0.072f, 22f), 56f);
        return baseDp * d;
    }

    private float densidade() {
        try {
            float d = getResources().getDisplayMetrics().density;
            return d > 0.1f ? d : 1f;
        } catch (Exception e) {
            return 1f;   // fora do aparelho (testes de mesa)
        }
    }

    private void remontar() {
        blocos.clear();
        topos.clear();
        alturaTexto = 0;
        faixaY = margemTopo + Math.round(alturaUtil() * FAIXA);

        int largura = getWidth() - Math.round(getWidth() * 0.06f);
        if (largura <= 0 || linhas.isEmpty()) { rolagemMax = 0; return; }

        float corpo = corpoBase() * fonteFator;
        int y = 0;

        for (int i = 0; i < linhas.size(); i++) {
            TextoParser.Linha l = linhas.get(i);
            tinta.setTextSize(corpoTipo(l.tipo, corpo));
            String texto = l.texto == null ? "" : l.texto;

            StaticLayout sl = new StaticLayout(texto, tinta, largura,
                    Layout.Alignment.ALIGN_NORMAL, 1.12f, 0f, false);
            blocos.add(sl);
            topos.add(y);
            int alt = texto.length() == 0 ? Math.round(corpo * 0.55f) : sl.getHeight();
            y += alt + Math.round(corpo * 0.18f);
        }
        alturaTexto = y;

        // ── Onde a rolagem TEM que parar ────────────────────
        //
        // Na FAIXA-GUIA, sempre. A ultima linha do texto termina no
        // mesmo lugar onde a primeira comecou, que e onde o olho de
        // quem le esta cravado o tempo todo.
        //
        // Repare no "ultima LINHA", e nao "ultimo paragrafo". Um fecho
        // de oito linhas nao pode parar com o TOPO dele na faixa: as
        // outras sete ficariam abaixo, e a de baixo cairia atras dos
        // botoes. Foi exatamente o que aconteceu — o locutor chegava no
        // fim e tinha que adivinhar a ultima frase.
        //
        // Uma linha de indice i e desenhada em  y = topos[i] - rolagem
        // + faixaY. Para a ULTIMA linha visual do paragrafo pousar na
        // faixa, entao:
        //
        //     rolagem = topos[ultimo] + (altura ate essa ultima linha)
        //
        // Num paragrafo de uma linha so, "altura ate a ultima linha" e
        // zero e a conta vira rolagem = topos[ultimo] — o mesmo caso
        // simples de antes. Os dois casos viraram um.
        //
        // A versao anterior tinha um piso separado que so garantia que
        // a ultima linha ficasse VISIVEL, rente a borda. Visivel nao
        // basta: rente a borda o olho tem que descer da faixa ate o pe
        // da tela justamente no fecho.
        int ultimo = (indiceFecho >= 0 && indiceFecho < topos.size())
                   ? indiceFecho : topos.size() - 1;
        rolagemMax = Math.max(0, topos.get(ultimo) + alturaAteAUltimaLinha(blocos.get(ultimo)));
        if (rolagem > rolagemMax) rolagem = rolagemMax;
    }

    /**
     * Quanto de um paragrafo fica ACIMA da sua ultima linha.
     *
     * Num paragrafo de uma linha, zero. Num de oito, a altura das sete
     * primeiras. E a distancia que a rolagem precisa percorrer a mais
     * para que a ultima linha — e nao o topo do paragrafo — chegue a
     * faixa-guia.
     */
    private int alturaAteAUltimaLinha(StaticLayout bloco) {
        int n = bloco.getLineCount();
        if (n <= 1) return 0;
        return bloco.getLineTop(n - 1);
    }

    /** Cue e nota sao menores: sao para bater o olho, nao para ler. */
    private float corpoTipo(char tipo, float corpo) {
        if (tipo == TextoParser.CUE)  return corpo * 0.74f;
        if (tipo == TextoParser.NOTA) return corpo * 0.68f;
        return corpo;
    }

    private int corTipo(char tipo, int indice) {
        if (indice == indiceFecho) return COR_FECHO;
        switch (tipo) {
            case TextoParser.RETRANCA: return COR_RETRANCA;
            case TextoParser.CUE:      return COR_CUE;
            case TextoParser.DEIXA:    return COR_DEIXA;
            case TextoParser.NOTA:     return COR_NOTA;
            default:                   return COR_LEITURA;
        }
    }

    // ── Guardar o lugar da leitura ───────────────────────────
    private int linhaNaFaixa() {
        float alvo = rolagem + faixaY;
        for (int i = 0; i < topos.size(); i++) {
            int fim = topos.get(i) + blocos.get(i).getHeight();
            if (fim > alvo) return i;
        }
        return topos.isEmpty() ? -1 : topos.size() - 1;
    }

    private void irParaLinha(int indice) {
        if (indice < 0 || indice >= topos.size()) return;
        rolagem = Math.max(0, Math.min(topos.get(indice) - faixaY, rolagemMax));
    }

    // ── Desenho ──────────────────────────────────────────────
    @Override protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();

        // Sem camera, fundo preto de verdade. Com camera, um veu escuro
        // que deixa a imagem passar mas ainda da contraste para a letra —
        // texto amarelo sobre rosto claro fica ilegivel sem isto.
        canvas.drawColor(fundoEscuro ? 0xFF080800 : 0x99000000);

        // Faixa-guia: onde o olho fica cravado.
        pincel.setColor(0x1AF5E642);
        canvas.drawRect(0, faixaY, w, faixaY + corpoBase() * 1.9f, pincel);
        pincel.setColor(0x8CF5E642);
        canvas.drawRect(w * 0.02f, faixaY, w * 0.98f, faixaY + 2f, pincel);

        float margem = w * 0.03f;
        float corpo = corpoBase() * fonteFator;

        // O espelho vale para o TEXTO, e so para ele. A faixa-guia ja
        // foi desenhada acima e e simetrica; as barras e os botoes sao
        // desenhados por fora desta View e continuam legiveis de pe, do
        // lado de ca do vidro, para quem estiver operando.
        int espelho = canvas.save();
        if (espelhado) canvas.scale(-1f, 1f, w / 2f, 0f);

        for (int i = 0; i < blocos.size(); i++) {
            float y = topos.get(i) - rolagem + faixaY;
            float alt = blocos.get(i).getHeight();
            if (y + alt < 0) continue;          // ja passou por cima
            if (y > h) break;                   // ainda nem chegou

            tinta.setTextSize(corpoTipo(linhas.get(i).tipo, corpo));
            tinta.setColor(corTipo(linhas.get(i).tipo, i));

            int s2 = canvas.save();
            canvas.translate(margem, y);
            blocos.get(i).draw(canvas);
            canvas.restoreToCount(s2);
        }

        canvas.restoreToCount(espelho);
    }

    @Override protected void onDetachedFromWindow() {
        pararMotor();
        super.onDetachedFromWindow();
    }
}
