import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Clase para gestionar la comunicación con un servidor FTP */
public class ClientFtpProtocolService implements Runnable {

    /** Socket para la comunicación con el servidor en el canal de control */
    private Socket controlSckt;
    /** Lector de respuestas del servidor FTP */
    private BufferedReader reader;
    /** Salida donde se registran los comandos y respuestas */
    private OutputStream outputStream;
    /** Escritor para enviar comandos al servidor */
    private PrintWriter writer;
    /** Hilo que maneja la comunicación en el canal de control */
    private Thread controlThrd;
    /** Latch para sincronizar la respuesta del comando PASV */
    private CountDownLatch pasvLatch;
    /** Socket para la comunicación */
    private Socket dataSckt;
    /** Objeto de bloqueo para sincronización del canal de datos */
    private final Object dataChannelLock = new Object();
    /** Indicador de uso del canal de datos */
    private final AtomicBoolean dataChannelInUse = new AtomicBoolean(false);

    /**
     * Constructor del servicio FTP
     * 
     * @param outputStream Salida donde se registrarán los comandos y respuestas
     */
    public ClientFtpProtocolService(OutputStream outputStream) {
        this.outputStream = outputStream;
    }

    /**
     * Conecta con el servidor FTP en el puerto especificado.
     * 
     * @param server Dirección del servidor FTP
     * @param port   Puerto de conexión
     * @throws IOException
     */
    public void connectTo(String server, int port) throws IOException {
        controlSckt = new Socket(server, port);
        reader = new BufferedReader(new InputStreamReader(controlSckt.getInputStream()));
        writer = new PrintWriter(controlSckt.getOutputStream(), true);
        controlThrd = new Thread(this);
        controlThrd.start();
    }

    /**
     * Autentica al usuario en el servidor FTP
     * 
     * @param user Nombre del usuario
     * @param pass Contraseña
     */
    public void authenticate(String user, String pass) {
        sendCommand("USER " + user);
        sendCommand("PASS " + pass);
    }

    /**
     * Cierra la conexión con el servidor FTP
     */
    public void close() {
        sendCommand("QUIT");
        try {
            if (controlSckt != null && !controlSckt.isClosed()) {
                controlSckt.close();
            }
        } catch (IOException e) {
            System.err.println("Error al cerrar la conexión: " + e.getMessage());
        }
    }

    /**
     * Envía el comando QUIT para cerrar la conexión con el servidor
     * 
     * @return Comando
     */
    public String sendQuit() {
        String command = "QUIT";
        sendCommand(command);
        return command;
    }

    /**
     * Envía el comando PWD para obtener el directorio de trabajo actual
     * 
     * @return Comando
     */
    public String sendPwd() {
        String command = "PWD";
        sendCommand(command);
        return command;
    }

    /**
     * Envía el comando CWD para cambiar de directorio
     * 
     * @param down
     * @return Comando
     */
    public String sendCwd(String down) {
        String command = "CWD " + down;
        sendCommand(command);
        return command;
    }

    /**
     * Envía el comando CDUP para subir al directorio padre.
     * 
     * @return Comando
     */
    public String sendCdup() {
        String command = "CDUP";
        sendCommand(command);
        return command;
    }

    /**
     * Envía el comando PASV
     * 
     * @return Comando enviado
     */
    public String sendPassv() {
        synchronized (dataChannelLock) {
            while (dataChannelInUse.get()) {
                try {
                    dataChannelLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            dataChannelInUse.set(true);
        }
        pasvLatch = new CountDownLatch(1);
        sendCommand("PASV");
        try {
            if (!pasvLatch.await(10, TimeUnit.SECONDS)) {
                System.err.println("Tiempo de espera agotado para respuesta PASV");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("Interrumpido esperando respuesta PASV");
        }
        return "PASV";
    }

    /**
     * Envía el comando RETR para descargar un archivo remoto
     * 
     * @param remote
     * @param out
     * @param closeOutput
     * @return Comando
     */
    public String sendRetr(String remote, OutputStream out, boolean closeOutput) {
        String command = "RETR " + remote;
        sendCommand(command);
        if (dataSckt == null) {
            System.err.println("Canal de datos no iniciado. Llama a sendPassv() antes de RETR.");
            return null;
        }
        ClientFtpDataService dataSrv = new ClientFtpDataService(dataSckt, out, closeOutput, dataChannelLock,
                dataChannelInUse);
        new Thread(dataSrv).start();
        dataSckt = null;
        return command;
    }

    /**
     * Envía el comando LIST para listar los archivos en el directorio actual
     * 
     * @param out
     * @param closeOutput
     * @return Comando
     */
    public String sendList(OutputStream out, boolean closeOutput) {
        String command = "LIST";
        sendCommand(command);
        if (dataSckt == null) {
            System.err.println("Canal de datos no iniciado. Llama a sendPassv() antes de LIST.");
            return null;
        }
        ClientFtpDataService dataSrv = new ClientFtpDataService(dataSckt, out, closeOutput, dataChannelLock,
                dataChannelInUse);
        new Thread(dataSrv).start();
        dataSckt = null;
        return command;
    }

    /**
     * Ejecuta el hilo para leer respuestas del servidor FTP
     */
    @Override
    public void run() {
        String line;
        try {
            while ((line = reader.readLine()) != null) {
                outputStream.write((line + "\n").getBytes());
                if (line.startsWith("227")) {
                    InetSocketAddress dataAddress = parse227(line);
                    try {
                        dataSckt = new Socket(dataAddress.getHostName(), dataAddress.getPort());
                    } catch (IOException e) {
                        outputStream.write(
                                ("Error al conectar con el canal de datos: " + e.getMessage() + "\n").getBytes());
                    }
                    if (pasvLatch != null) {
                        pasvLatch.countDown();
                    }
                }
            }
        } catch (IOException e) {
            try {
                outputStream.write(("Error en el canal de control: " + e.getMessage() + "\n").getBytes());
            } catch (IOException e2) {
                e2.printStackTrace();
            }
        }
    }

    /**
     * Parsea la respuesta PASV del servidor FTP para obtener la dirección y puerto
     * del canal de datos
     * 
     * @param response
     * @return Dirección y puerto del canal de datos o null si ocurre un error
     */
    private InetSocketAddress parse227(String response) {
        try {
            Pattern pattern = Pattern.compile(".*\\((\\d+),(\\d+),(\\d+),(\\d+),(\\d+),(\\d+)\\).*");
            Matcher matcher = pattern.matcher(response);
            if (matcher.find()) {
                String ip = matcher.group(1) + "." + matcher.group(2) + "." + matcher.group(3) + "." + matcher.group(4);
                int port = Integer.parseInt(matcher.group(5)) * 256 + Integer.parseInt(matcher.group(6));
                return new InetSocketAddress(ip, port);
            }
        } catch (Exception e) {
            System.err.println("Error al parsear la respuesta PASV: " + e.getMessage());
        }
        return null;
    }

    /**
     * Envía un comando al servidor FTP
     * 
     * @param command Comando FTP a enviar
     */
    private void sendCommand(String command) {
        try {
            if (writer != null) {
                writer.println(command);
                outputStream.write((command + "\n").getBytes());
            }
        } catch (IOException e) {
            System.err.println("Error al enviar el comando: " + command + " - " + e.getMessage());
        }
    }
}
