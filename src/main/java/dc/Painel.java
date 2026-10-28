package dc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Ponte entre a página docs/demo-online.html e os processos Java reais deste computador.
 * A página não consegue subir processos; este servidor HTTP faz isso por ela:
 *  - GET  /              serve a página (com o token embutido);
 *  - GET  /eventos       fluxo SSE com a saída de cada terminal (uma linha = um evento);
 *  - POST /lancar        sobe ./rodar.sh <Classe> <args> no terminal indicado;
 *  - POST /entrada       escreve uma linha no stdin do processo (q/n dos sensores, comandos do cliente);
 *  - POST /parar         encerra o processo (equivale ao Ctrl+C);
 *  - POST /quadro-gigante abre uma conexão TCP com o gateway e envia um tamanho de 2 GiB (teste de robustez).
 *
 * Segurança: escuta só em 127.0.0.1; toda chamada exige o token sorteado a cada execução; o cabeçalho
 * Host é conferido (contra DNS rebinding); só as cinco classes do projeto sobem, com argumentos filtrados;
 * a página que contém o token não envia cabeçalhos CORS, então outro site não consegue lê-la.
 */
public class Painel {
    private static final Set<String> CLASSES = Set.of("Gateway", "ArCondicionado", "SensorTemperatura", "Pdu", "Cliente");
    private static final Pattern ARG = Pattern.compile("[A-Za-z0-9._-]{1,40}");
    private static final Pattern OPCAO = Pattern.compile("-D(dc\\.descoberta\\.s=\\d{1,3}|dc\\.interface=[A-Za-z0-9._-]{1,20})");
    private static final Pattern TERMINAL = Pattern.compile("t\\d{1,2}");

    private final int porta;
    private final String token;
    private final Path pagina = Path.of("docs", "demo-online.html");
    private final Map<String, Process> processos = new ConcurrentHashMap<>();
    private final List<OutputStream> assinantes = new CopyOnWriteArrayList<>();

    Painel(int porta) {
        this.porta = porta;
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        this.token = sb.toString();
    }

    public static void main(String[] args) throws Exception {
        int porta = args.length > 0 ? Integer.parseInt(args[0]) : 8099;
        new Painel(porta).iniciar();
    }

    void iniciar() throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), porta), 0);
        http.setExecutor(Executors.newCachedThreadPool());
        http.createContext("/", this::pagina);
        http.createContext("/ping", ex -> api(ex, false, () -> texto(ex, 200, "{\"painel\":true}")));
        http.createContext("/estado", ex -> api(ex, true, () -> texto(ex, 200, "ok")));
        http.createContext("/eventos", ex -> api(ex, true, () -> assinar(ex)));
        http.createContext("/lancar", ex -> api(ex, true, () -> lancar(ex)));
        http.createContext("/entrada", ex -> api(ex, true, () -> entrada(ex)));
        http.createContext("/parar", ex -> api(ex, true, () -> parar(ex)));
        http.createContext("/quadro-gigante", ex -> api(ex, true, () -> quadroGigante(ex)));
        http.start();

        // comentário periódico: derruba assinantes cuja aba foi fechada
        Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; })
                .scheduleWithFixedDelay(() -> enviarBruto(": batimento\n\n"), 15, 15, TimeUnit.SECONDS);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> processos.values().forEach(Process::destroy)));

        System.out.println("[painel] http://localhost:" + porta + "/   (abra no navegador)");
        System.out.println("[painel] token para a página pública: " + token);
        System.out.println("[painel] Ctrl+C encerra o painel e os processos que ele subiu");
    }

    // ------------------------------------------------------------------ HTTP

    private interface Acao { void executar() throws Exception; }

    /** Moldura das chamadas de API: Host, CORS, token e tratamento de erro. */
    private void api(HttpExchange ex, boolean exigeToken, Acao acao) throws IOException {
        try {
            if (!hostValido(ex)) { texto(ex, 403, "host inválido"); return; }
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().set("Access-Control-Allow-Private-Network", "true");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            if (ex.getRequestMethod().equals("OPTIONS")) { ex.sendResponseHeaders(204, -1); ex.close(); return; }
            if (exigeToken && !tokenValido(ex)) { texto(ex, 401, "token inválido"); return; }
            acao.executar();
        } catch (Exception e) {
            try { texto(ex, 500, "erro: " + e.getMessage()); } catch (IOException ignorada) { /* conexão já fechada */ }
        }
    }

    private boolean hostValido(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Host");
        return h != null && (h.equals("localhost:" + porta) || h.equals("127.0.0.1:" + porta));
    }

    private boolean tokenValido(HttpExchange ex) {
        String t = consulta(ex).get("t");
        return t != null && MessageDigest.isEqual(t.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }

    private static Map<String, String> consulta(HttpExchange ex) {
        String q = ex.getRequestURI().getRawQuery();
        return q == null ? Map.of() : formulario(q);
    }

    private static Map<String, String> formulario(String s) {
        Map<String, String> m = new HashMap<>();
        for (String par : s.split("&")) {
            int i = par.indexOf('=');
            if (i < 0) continue;
            m.put(URLDecoder.decode(par.substring(0, i), StandardCharsets.UTF_8),
                    URLDecoder.decode(par.substring(i + 1), StandardCharsets.UTF_8));
        }
        return m;
    }

    private static String corpo(HttpExchange ex) throws IOException {
        byte[] b = ex.getRequestBody().readNBytes(4096);
        return new String(b, StandardCharsets.UTF_8);
    }

    private static void texto(HttpExchange ex, int codigo, String msg) throws IOException {
        byte[] b = msg.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(codigo, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    /** Página servida com o token embutido. Sem CORS: outro site não consegue ler o token. */
    private void pagina(HttpExchange ex) throws IOException {
        if (!hostValido(ex)) { texto(ex, 403, "host inválido"); return; }
        String caminho = ex.getRequestURI().getPath();
        if (caminho.equals("/lm-fontes.css") || caminho.equals("/ufc_logo.png")) {    // ativos públicos da página, sem token
            Path arq = Path.of("docs", caminho.substring(1));
            if (!Files.exists(arq)) { texto(ex, 404, "não encontrado"); return; }
            byte[] b = Files.readAllBytes(arq);
            ex.getResponseHeaders().set("Content-Type", caminho.endsWith(".css") ? "text/css; charset=utf-8" : "image/png");
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(b); }
            return;
        }
        if (!caminho.equals("/") && !caminho.equals("/index.html") && !caminho.equals("/demo-online.html")) {
            texto(ex, 404, "não encontrado");
            return;
        }
        if (!Files.exists(pagina)) { texto(ex, 404, "docs/demo-online.html não encontrado: rode o painel na pasta do projeto"); return; }
        String html = Files.readString(pagina, StandardCharsets.UTF_8)
                .replace("</head>", "<script>window.DC_PAINEL = {\"token\":\"" + token + "\"};</script>\n</head>");
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream o = ex.getResponseBody()) { o.write(b); }
    }

    // ------------------------------------------------------------------ eventos (SSE)

    private void assinar(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);           // 0 = resposta em pedaços, fica aberta
        OutputStream o = ex.getResponseBody();
        o.write(": conectado\n\n".getBytes(StandardCharsets.UTF_8));
        o.flush();
        assinantes.add(o);                        // a conexão segue aberta; quem escreve são as threads dos processos
    }

    private void enviar(String terminal, String tipo, String texto) {
        enviarBruto("data: {\"term\":\"" + terminal + "\",\"tipo\":\"" + tipo + "\",\"texto\":\"" + json(texto) + "\"}\n\n");
    }

    private void enviarBruto(String quadro) {
        byte[] b = quadro.getBytes(StandardCharsets.UTF_8);
        for (OutputStream o : assinantes) {
            try {
                synchronized (o) { o.write(b); o.flush(); }
            } catch (IOException e) {
                assinantes.remove(o);
                try { o.close(); } catch (IOException ignorada) { /* já caiu */ }
            }
        }
    }

    private static String json(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ processos

    private void lancar(HttpExchange ex) throws Exception {
        Map<String, String> f = formulario(corpo(ex));
        String terminal = f.getOrDefault("term", ""), classe = f.getOrDefault("classe", "");
        if (!TERMINAL.matcher(terminal).matches()) { texto(ex, 400, "terminal inválido"); return; }
        if (!CLASSES.contains(classe)) { texto(ex, 400, "classe não permitida: " + classe); return; }
        List<String> cmd = new ArrayList<>(List.of("./rodar.sh", classe));
        for (String a : f.getOrDefault("args", "").trim().split("\\s+")) {
            if (a.isEmpty()) continue;
            if (!ARG.matcher(a).matches()) { texto(ex, 400, "argumento inválido: " + a); return; }
            cmd.add(a);
        }
        List<String> opcoes = new ArrayList<>(List.of("-Dstdout.encoding=UTF-8", "-Dfile.encoding=UTF-8"));
        for (String o : f.getOrDefault("opts", "").trim().split("\\s+")) {
            if (o.isEmpty()) continue;
            if (!OPCAO.matcher(o).matches()) { texto(ex, 400, "opção não permitida: " + o); return; }
            opcoes.add(o);
        }
        Process existente = processos.get(terminal);
        if (existente != null && existente.isAlive()) { texto(ex, 409, "o terminal já tem um processo rodando"); return; }

        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("JAVA_OPTS", String.join(" ", opcoes));
        Process p = pb.start();
        processos.put(terminal, p);
        Thread leitor = new Thread(() -> ler(terminal, classe, p), "saida-" + terminal);
        leitor.setDaemon(true);
        leitor.start();
        texto(ex, 200, "ok");
    }

    /** Uma linha de saída = um evento. O prompt "dc> " do cliente fica de fora: a página mostra o seu. */
    private void ler(String terminal, String classe, Process p) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String linha;
            while ((linha = r.readLine()) != null) {
                if (classe.equals("Cliente")) while (linha.startsWith("dc> ")) linha = linha.substring(4);
                enviar(terminal, "saida", linha);
            }
        } catch (IOException ignorada) {
            // processo encerrado: o fluxo fecha
        }
        int codigo;
        try { codigo = p.waitFor(); } catch (InterruptedException e) { codigo = -1; }
        processos.remove(terminal, p);
        enviar(terminal, "fim", String.valueOf(codigo));
    }

    private void entrada(HttpExchange ex) throws Exception {
        Map<String, String> f = formulario(corpo(ex));
        Process p = processos.get(f.getOrDefault("term", ""));
        String linha = f.getOrDefault("linha", "");
        if (p == null || !p.isAlive()) { texto(ex, 404, "sem processo nesse terminal"); return; }
        if (linha.length() > 200 || linha.indexOf('\n') >= 0) { texto(ex, 400, "linha inválida"); return; }
        OutputStream in = p.getOutputStream();
        synchronized (in) { in.write((linha + "\n").getBytes(StandardCharsets.UTF_8)); in.flush(); }
        texto(ex, 200, "ok");
    }

    private void parar(HttpExchange ex) throws Exception {
        Map<String, String> f = formulario(corpo(ex));
        Process p = processos.get(f.getOrDefault("term", ""));
        if (p != null) p.destroy();
        texto(ex, 200, "ok");
    }

    /** Mesmo teste do slide 11: tamanho de quadro 0x7fffffff (2 GiB); o gateway deve fechar só essa conexão. */
    private void quadroGigante(HttpExchange ex) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", Config.GATEWAY_TCP_PORTA), 1000);
            s.getOutputStream().write(new byte[]{0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff});
            s.getOutputStream().flush();
            Thread.sleep(300);
        } catch (IOException e) {
            texto(ex, 503, "gateway não está no ar");
            return;
        }
        texto(ex, 200, "ok");
    }
}
