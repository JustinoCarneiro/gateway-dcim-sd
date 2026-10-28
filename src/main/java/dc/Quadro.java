package dc;

import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Enquadramento de mensagens Protobuf sobre TCP.
 * TCP é um fluxo de bytes, sem fronteiras de mensagem: cada mensagem vai como
 * [4 bytes big-endian com o tamanho][bytes Protobuf].
 */
public final class Quadro {
    private Quadro() {}

    /** Tamanho declarado fora do limite: o fluxo não é confiável, a conexão deve ser fechada. */
    public static class QuadroInvalidoException extends IOException {
        public QuadroInvalidoException(String msg) {
            super(msg);
        }
    }

    public static void enviar(OutputStream out, MessageLite msg) throws IOException {
        byte[] corpo = msg.toByteArray();
        // cabeçalho e corpo num único write, para sair num só segmento TCP
        out.write(ByteBuffer.allocate(4 + corpo.length).putInt(corpo.length).put(corpo).array());
        out.flush();
    }

    /**
     * Lê um quadro inteiro e o decodifica. Lança EOFException se a conexão fechar
     * e InvalidProtocolBufferException se o conteúdo for inválido (nesse caso o quadro
     * já foi consumido, então o fluxo continua alinhado e a conexão pode seguir).
     */
    public static <T extends MessageLite> T receber(DataInputStream in, Parser<T> parser) throws IOException {
        int tamanho = in.readInt();   // bloqueia até haver 4 bytes
        if (tamanho < 0 || tamanho > Config.TAMANHO_MAX_QUADRO) {
            throw new QuadroInvalidoException("quadro de " + tamanho + " bytes fora do limite");
        }
        byte[] corpo = new byte[tamanho];
        in.readFully(corpo);          // read() solto poderia devolver só parte do quadro
        return parser.parseFrom(corpo);
    }
}
