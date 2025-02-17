import java.io.IOException;

/**
 * Clase principal
 * 
 * @author Natalia Rey Loroño
 */
public class Main {
    /**
     * Método principal para la ejecución del programa
     * 
     * @param args
     */
    public static void main(String[] args) {
        try {
            // Crear cliente FTP con salida estándar
            ClientFtpProtocolService ftpClient = new ClientFtpProtocolService(System.out);

            // Conectar al servidor FTP de prueba
            System.out.println("Conectando al servidor FTP...");
            ftpClient.connectTo("ftp.dlptest.com", 21);

            // Autenticar con usuario y contraseña
            System.out.println("Autenticando...");
            ftpClient.authenticate("dlpuser", "rNrKYTX9g7z3RgJRmxWuGHbeu");

            // Obtener y mostrar el directorio actual
            System.out.println("Obteniendo el directorio actual...");
            ftpClient.sendPwd();

            // Activar modo pasivo y listar archivos del directorio actual
            System.out.println("Activando modo pasivo...");
            ftpClient.sendPassv();
            System.out.println("Listando archivos...");
            ftpClient.sendList(System.out, false);

            // Cerrar la conexión con el servidor FTP
            System.out.println("Cerrando la conexión...");
            ftpClient.sendQuit();
            ftpClient.close();
            System.out.println("Conexión cerrada correctamente.");
        } catch (IOException e) {
            System.err.println("Error en la comunicación FTP: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
