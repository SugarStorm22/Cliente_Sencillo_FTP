import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Clase para gestionar la recepción de datos en un canal FTP
 * 
 * @author Natalia Rey Loroño
 */
public class ClientFtpDataService implements Runnable {
    /** Socket de datos conectado al servidor FTP */
    private Socket dataSckt;
    /** Flujo de salida donde se almacenarán los datos recibidos */
    private OutputStream outputStream;
    /** Indica si se debe cerrar el flujo de salida después de la transferencia */
    private boolean close;
    /** Objeto de bloqueo para sincronizar el canal de datos */
    private final Object dataChannelLock;
    /** Indicador de uso del canal de datos */
    private final AtomicBoolean dataChannelInUse;

    /**
     * Constructor para gestionar la transferencia de datos desde el servidor FTP
     * 
     * @param dataSckt
     * @param out
     * @param closeOutput
     * @param dataChannelLock
     * @param dataChannelInUse
     */
    public ClientFtpDataService(Socket dataSckt, OutputStream out, boolean closeOutput, Object dataChannelLock,
            AtomicBoolean dataChannelInUse) {
        this.dataSckt = dataSckt;
        this.outputStream = out;
        this.close = closeOutput;
        this.dataChannelLock = dataChannelLock;
        this.dataChannelInUse = dataChannelInUse;
    }

    /**
     * Método ejecutado en un hilo independiente para leer los datos del servidor y
     * escribirlos en el flujo de salida
     */
    @Override
    public void run() {
        try (InputStream in = dataSckt.getInputStream()) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
            }
            outputStream.flush();
        } catch (IOException e) {
            System.err.println("Error al recibir datos: " + e.getMessage());
        } finally {
            closeResources();
        }
    }

    /**
     * Cierra los recursos utilizados
     */
    private void closeResources() {
        try {
            if (dataSckt != null && !dataSckt.isClosed()) {
                dataSckt.close();
            }
        } catch (IOException e) {
            System.err.println("Error al cerrar el socket de datos: " + e.getMessage());
        }
        if (close) {
            try {
                outputStream.close();
            } catch (IOException e) {
                System.err.println("Error al cerrar el flujo de salida: " + e.getMessage());
            }
        }
        synchronized (dataChannelLock) {
            dataChannelInUse.set(false);
            dataChannelLock.notifyAll();
        }
    }
}
