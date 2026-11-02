package dc;

import dc.proto.Grandeza;
import dc.proto.Leitura;
import dc.proto.TipoFonte;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Persistência das leituras em CSV (uma linha por leitura, em ordem de chegada).
 * O arquivo só cresce (append); ao reiniciar, o gateway recarrega a parte que ainda está
 * dentro da retenção em memória. O arquivo inteiro serve às consultas de histórico.
 *
 * Colunas: recebido_em (ISO, só para leitura humana), recebido_em_ms, fonte_id, tipo, rack,
 * grandeza, valor, timestamp_ms, evento, seq, intervalo_ms.
 */
public class HistoricoCsv {
    private static final String CABECALHO =
            "recebido_em,recebido_em_ms,fonte_id,tipo,rack,grandeza,valor,timestamp_ms,evento,seq,intervalo_ms";

    private final Path caminho;
    private BufferedWriter escritor;      // aberto na primeira gravação
    private boolean avisouFalha;

    public HistoricoCsv(Path caminho) {
        this.caminho = caminho;
    }

    public Path caminho() {
        return caminho;
    }

    /**
     * Acrescenta uma leitura. Falha de disco não pode derrubar a recepção UDP: registra
     * o problema uma vez e segue (as consultas continuam funcionando com a memória).
     */
    public synchronized void gravar(Cadastro.Registro r) {
        try {
            if (escritor == null) abrir();
            Leitura l = r.leitura();
            escritor.write(String.join(",",
                    Instant.ofEpochMilli(r.recebidoEm()).toString(),
                    Long.toString(r.recebidoEm()),
                    campo(l.getFonteId()),
                    l.getTipo().name(),
                    campo(l.getRack()),
                    l.getGrandeza().name(),
                    Double.toString(l.getValor()),
                    Long.toString(l.getTimestampMs()),
                    Boolean.toString(l.getEvento()),
                    Long.toUnsignedString(l.getSeq()),
                    Integer.toString(l.getIntervaloMs())));
            escritor.newLine();
            escritor.flush();   // cada leitura chega a ~2 s: flush simples, sem perder dados num kill
        } catch (IOException e) {
            if (!avisouFalha) {
                System.out.println("[csv] falha ao gravar " + caminho + ": " + e + " (seguindo só em memória)");
                avisouFalha = true;
            }
            escritor = null;   // tenta reabrir na próxima leitura
        }
    }

    private void abrir() throws IOException {
        Path pasta = caminho.toAbsolutePath().getParent();
        if (pasta != null) Files.createDirectories(pasta);
        boolean novo = !Files.exists(caminho) || Files.size(caminho) == 0;
        escritor = Files.newBufferedWriter(caminho, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (novo) {
            escritor.write(CABECALHO);
            escritor.newLine();
        }
        avisouFalha = false;
    }

    /** Leituras com recebido_em_ms >= desdeMs, em ordem de chegada. Linhas inválidas são puladas. */
    public synchronized List<Cadastro.Registro> carregar(long desdeMs) {
        List<Cadastro.Registro> lista = new ArrayList<>();
        lerLinhas(r -> {
            if (r.recebidoEm() >= desdeMs) lista.add(r);
        });
        return lista;
    }

    /**
     * As últimas {@code limite} leituras do arquivo, da mais antiga para a mais recente.
     * fonteId vazio = todas as fontes.
     */
    public synchronized List<Cadastro.Registro> ultimos(String fonteId, int limite) {
        Deque<Cadastro.Registro> janela = new ArrayDeque<>();
        lerLinhas(r -> {
            if (!fonteId.isEmpty() && !r.leitura().getFonteId().equals(fonteId)) return;
            janela.addLast(r);
            if (janela.size() > limite) janela.removeFirst();
        });
        return new ArrayList<>(janela);
    }

    private void lerLinhas(java.util.function.Consumer<Cadastro.Registro> destino) {
        if (!Files.exists(caminho)) return;
        int puladas = 0;
        try (BufferedReader leitor = Files.newBufferedReader(caminho, StandardCharsets.UTF_8)) {
            String linha = leitor.readLine();   // cabeçalho
            while ((linha = leitor.readLine()) != null) {
                if (linha.isBlank()) continue;
                try {
                    destino.accept(interpretar(linha));
                } catch (RuntimeException e) {
                    puladas++;   // linha truncada (queda no meio da escrita) ou editada à mão
                }
            }
        } catch (IOException e) {
            System.out.println("[csv] falha ao ler " + caminho + ": " + e);
        }
        if (puladas > 0) System.out.println("[csv] " + puladas + " linha(s) inválida(s) ignorada(s)");
    }

    static Cadastro.Registro interpretar(String linha) {
        List<String> c = dividir(linha);
        if (c.size() != 11) throw new IllegalArgumentException("colunas: " + c.size());
        Leitura l = Leitura.newBuilder()
                .setFonteId(c.get(2))
                .setTipo(TipoFonte.valueOf(c.get(3)))
                .setRack(c.get(4))
                .setGrandeza(Grandeza.valueOf(c.get(5)))
                .setValor(Double.parseDouble(c.get(6)))
                .setTimestampMs(Long.parseLong(c.get(7)))
                .setEvento(Boolean.parseBoolean(c.get(8)))
                .setSeq(Long.parseUnsignedLong(c.get(9)))
                .setIntervaloMs(Integer.parseInt(c.get(10)))
                .build();
        return new Cadastro.Registro(l, Long.parseLong(c.get(1)));
    }

    /** Aspas só quando o texto tem vírgula, aspas ou quebra de linha (RFC 4180). */
    static String campo(String s) {
        if (s.indexOf(',') < 0 && s.indexOf('"') < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s;
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    static List<String> dividir(String linha) {
        List<String> campos = new ArrayList<>();
        StringBuilder atual = new StringBuilder();
        boolean aspas = false;
        for (int i = 0; i < linha.length(); i++) {
            char ch = linha.charAt(i);
            if (aspas) {
                if (ch == '"' && i + 1 < linha.length() && linha.charAt(i + 1) == '"') {
                    atual.append('"');
                    i++;
                } else if (ch == '"') {
                    aspas = false;
                } else {
                    atual.append(ch);
                }
            } else if (ch == '"') {
                aspas = true;
            } else if (ch == ',') {
                campos.add(atual.toString());
                atual.setLength(0);
            } else {
                atual.append(ch);
            }
        }
        campos.add(atual.toString());
        return campos;
    }
}
