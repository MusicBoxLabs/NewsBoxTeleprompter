package br.com.newsboxteleprompter;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Os textos da PASTA DE NOTICIAS, guardados neste aparelho.
 *
 * Antes eles moravam na caixinha e chegavam por HTTP. Agora entram pelo
 * app (IMPORTAR, ou Compartilhar > NewsBox), como no Music Box: o
 * arquivo .txt e copiado para a pasta privada do app e a lista sai
 * daqui. A conta de palavras e do tempo de locucao e a mesma regra de
 * antes: 160 palavras por minuto.
 */
public final class TextosLocais {

    /** Um texto na lista. */
    public static final class Texto {
        public final String arquivo, nome;
        public final int palavras, tempoSeg;
        Texto(String arquivo, String nome, int palavras, int tempoSeg) {
            this.arquivo = arquivo; this.nome = nome;
            this.palavras = palavras; this.tempoSeg = tempoSeg;
        }
    }

    static final int PALAVRAS_POR_MINUTO = 160;
    private static final String PASTA = "textos";
    private static final int LIMITE_BYTES = 1024 * 1024;

    private TextosLocais() { }

    private static File pasta(Context c) {
        File f = new File(c.getFilesDir(), PASTA);
        //noinspection ResultOfMethodCallIgnored
        f.mkdirs();
        return f;
    }

    public static List<Texto> listar(Context c) {
        List<Texto> saida = new ArrayList<>();
        File[] arquivos = pasta(c).listFiles();
        if (arquivos == null) return saida;
        Arrays.sort(arquivos, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        for (File f : arquivos) {
            String n = f.getName();
            if (!f.isFile() || !n.toLowerCase(Locale.ROOT).endsWith(".txt")) continue;
            String bruto;
            try { bruto = lerArquivo(f); } catch (IOException e) { continue; }
            int palavras = TextoParser.palavrasFaladas(TextoParser.lerTexto(bruto));
            int tempo = palavras * 60 / PALAVRAS_POR_MINUTO;
            saida.add(new Texto(n, n.substring(0, n.length() - 4), palavras, tempo));
        }
        return saida;
    }

    public static String ler(Context c, Texto t) throws IOException {
        return lerArquivo(new File(pasta(c), t.arquivo));
    }

    /** Copia um .txt escolhido no seletor de arquivos. Devolve o nome salvo. */
    public static String importar(Context c, Uri uri) throws IOException {
        String nome = nomeDoArquivo(c, uri);
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("o Android não abriu o arquivo");
        return gravarNovo(c, nome, lerTudo(in));
    }

    /** Texto recebido por Compartilhar (EXTRA_TEXT). */
    public static String importarTexto(Context c, String conteudo) throws IOException {
        return gravarNovo(c, "Texto compartilhado", conteudo.getBytes(StandardCharsets.UTF_8));
    }

    /** Formata segundos como m:ss, igual a lista da caixinha. */
    public static String mmss(int seg) {
        if (seg < 0) seg = 0;
        return (seg / 60) + ":" + (seg % 60 < 10 ? "0" : "") + (seg % 60);
    }

    // ── Encanamento ──────────────────────────────────────────
    private static String gravarNovo(Context c, String nomeBruto, byte[] dados) throws IOException {
        if (dados.length == 0) throw new IOException("o arquivo está vazio");
        if (dados.length > LIMITE_BYTES) throw new IOException("arquivo grande demais (mais de 1 MB)");
        for (byte b : dados) {
            if (b == 0) throw new IOException("isto não parece um texto (.txt)");
        }
        String base = limparNome(nomeBruto);
        File pasta = pasta(c);
        File destino = new File(pasta, base + ".txt");
        int k = 2;
        while (destino.exists()) destino = new File(pasta, base + " (" + k++ + ").txt");
        try (FileOutputStream out = new FileOutputStream(destino)) {
            out.write(dados);
        }
        return destino.getName();
    }

    private static String nomeDoArquivo(Context c, Uri uri) {
        String nome = null;
        try (Cursor cur = c.getContentResolver().query(uri, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int col = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (col >= 0) nome = cur.getString(col);
            }
        } catch (Exception e) {
            nome = null;
        }
        if (nome == null) nome = uri.getLastPathSegment();
        return nome == null ? "texto" : nome;
    }

    private static String limparNome(String nome) {
        String n = nome.trim();
        if (n.toLowerCase(Locale.ROOT).endsWith(".txt")) n = n.substring(0, n.length() - 4);
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return n.isEmpty() ? "texto" : n;
    }

    private static byte[] lerTudo(InputStream in) throws IOException {
        try (InputStream entrada = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] pedaco = new byte[8192];
            int n;
            while ((n = entrada.read(pedaco)) > 0) {
                buf.write(pedaco, 0, n);
                if (buf.size() > LIMITE_BYTES) break;   // o gravarNovo recusa
            }
            return buf.toByteArray();
        }
    }

    private static String lerArquivo(File f) throws IOException {
        byte[] dados;
        try (InputStream in = new java.io.FileInputStream(f)) { dados = lerTudo(in); }
        String s = new String(dados, StandardCharsets.UTF_8);
        if (s.startsWith("﻿")) s = s.substring(1);   // BOM de Bloco de Notas
        return s;
    }
}
