package br.com.newsboxteleprompter;

import java.util.ArrayList;
import java.util.List;

/**
 * Transforma um .txt cru nas linhas tipadas que a tela desenha.
 *
 * O Android ja traz org.json de graca, e a tentacao era usar. Nao foi
 * usado por um motivo pratico: assim TODA a conversa entre firmware e
 * app pode ser testada num PC comum, sem emulador e sem android.jar.
 * O teste desta classe roda contra o JSON de verdade, gerado pelo
 * proprio codigo C++ do firmware — nao contra um exemplo escrito a mao
 * que sempre concorda com quem o escreveu.
 *
 * O payload e um objeto plano, sem aninhamento e sem vetores. Este
 * leitor cobre exatamente isso, e recusa o resto em vez de fingir que
 * entendeu.
 */
public class TextoParser {

    /** Uma linha da pauta, ja com o tipo separado do texto. */
    public static class Linha {
        public final char tipo;     // T C D N L V
        public final String texto;
        public Linha(char tipo, String texto) { this.tipo = tipo; this.texto = texto; }
    }

    // ── Tipos de linha (os mesmos do firmware) ───────────────
    public static final char RETRANCA = 'T';
    public static final char CUE      = 'C';
    public static final char DEIXA    = 'D';
    public static final char NOTA     = 'N';
    public static final char LEITURA  = 'L';
    public static final char VAZIA    = 'V';

    /** Indice da ultima linha COM conteudo — o fecho da pauta. */
    /**
     * Le um .txt cru e devolve as linhas ja tipadas.
     *
     * A caixinha manda o arquivo exatamente como foi escrito — quem
     * classifica e o app. Assim qualquer editor de texto do mundo
     * produz um arquivo que este aparelho entende, e as regras de
     * marcacao existem num lugar so de cada lado.
     */
    public static List<Linha> lerTexto(String bruto) {
        List<Linha> saida = new ArrayList<>();
        if (bruto == null) return saida;
        String[] linhas = bruto.replace("\r\n", "\n").replace("\r", "\n").split("\n", -1);
        for (String l : linhas) {
            char tipo = classificar(l);
            saida.add(new Linha(tipo, tipo == VAZIA ? "" : limparMarcador(l, tipo)));
        }
        return saida;
    }

    /** As mesmas regras do firmware (10_textos.h), linha por linha. */
    public static char classificar(String linha) {
        String t = linha == null ? "" : linha.trim();
        if (t.length() == 0)    return VAZIA;
        if (t.startsWith("//")) return NOTA;
        if (t.startsWith("#"))  return RETRANCA;
        if (t.startsWith("["))  return CUE;
        if (t.startsWith(">"))  return DEIXA;
        return LEITURA;
    }

    /** Tira o simbolo da frente. O CUE mantem os colchetes de proposito. */
    public static String limparMarcador(String texto, char tipo) {
        String t = texto == null ? "" : texto.trim();
        if (tipo == NOTA && t.length() >= 2) t = t.substring(2);
        else if (tipo == RETRANCA) { while (t.startsWith("#")) t = t.substring(1); }
        else if (tipo == DEIXA && t.length() >= 1) t = t.substring(1);
        return t.trim();
    }

    public static int indiceDoFecho(List<Linha> linhas) {
        for (int i = linhas.size() - 1; i >= 0; i--) {
            if (linhas.get(i).texto.trim().length() > 0) return i;
        }
        return linhas.isEmpty() ? -1 : 0;
    }

    /** Conta o que sai pela boca: leitura e deixas. */
    public static int palavrasFaladas(List<Linha> linhas) {
        int n = 0;
        for (Linha l : linhas) {
            if (l.tipo != LEITURA && l.tipo != DEIXA) continue;
            boolean dentro = false;
            for (int i = 0; i < l.texto.length(); i++) {
                char c = l.texto.charAt(i);
                boolean sep = (c == ' ' || c == '\t' || c == '\n' || c == '\r');
                if (!sep && !dentro) { n++; dentro = true; }
                else if (sep) dentro = false;
            }
        }
        return n;
    }
}