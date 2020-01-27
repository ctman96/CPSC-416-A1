
import java.io.IOException;
import java.net.InetAddress;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.net.DatagramPacket;
import java.nio.ByteBuffer;

// This class sets up and controls the part of the system that responds to heartbeat messages

public class Responder {
    // Whether to debug log
    private static boolean DEBUG = false;

    // Socket data
    private int port;
    private InetAddress laddr;
    private DatagramSocket socket;

    // Handler
    private Thread thread;
    private ResponderHandler handler;


    // Runnable handler class to respond to packets
    private static class ResponderHandler implements Runnable {

        // Main toggle - if false discards received packets
        private boolean responding = false;

        // Parent Reference
        private Responder responder;

        private DatagramSocket socket;

        boolean isResponding() {
            return responding;
        }

        void setResponding(boolean responding) {
            this.responding = responding;
        }

        ResponderHandler(Responder responder, DatagramSocket socket) {
            this.responder = responder;
            this.socket = socket;
        }

        public void run() {
            while(true) {
                try {
                    // Receive packet
                    byte[] buf = new byte[1024];
                    // TODO: do we need to verify?
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);

                    // Discard packet if not responding
                    if (!responding) {
                        responder.log("Discarded - Not Responding");
                        continue;
                    }

                    // Process HeartBeat
                    InetAddress address = packet.getAddress();
                    int port = packet.getPort();

                    ByteBuffer bb = ByteBuffer.wrap(buf);
                    long epochNonce = bb.getLong();
                    long seqNum = bb.getLong();

                    responder.log("Handler Received: " + epochNonce + ", " + seqNum);

                    bb = ByteBuffer.allocate(16);
                    bb.putLong(epochNonce);
                    bb.putLong(seqNum);

                    // Reply Ack
                    DatagramPacket ack = new DatagramPacket(bb.array(), bb.position(), address, port);
                    socket.send(ack);

                } catch (IOException ex) {
                    responder.log("WARN: Caught exception in ResponseHandler: " + ex.getMessage());
                    // TODO do anything here?
                }
            }
        }
    }

    // This constructor creates a Responder object.
    //    port -- the port to listen for heartbeat messages
    //    laddr -- the IP address of the local interface to listen on. Keep in mind that a machine may
    //             have multiple interfaces and this is a way to select which interface to listen on.
    // If a  machine has multiple interfaces that require responders then multiple Responders can be used
    // to achieve this functonality. If the local ip address/port combination is invalid or in use then
    // a SocketException is to be thrown. Any other errors that result in a problem that would allow
    // things not to be monitored will result in a FailureDetectorException being thrown. 


    public Responder(int port, InetAddress laddr) throws FailureDetectorException, SocketException {
        this.port = port;
        this.laddr = laddr;
        try {
            socket = new DatagramSocket(port, laddr);
        } catch (SecurityException ex) {
            throw new FailureDetectorException("Security Exception");
        }
        handler = new ResponderHandler(this, socket);
        thread = new Thread(handler);
        thread.setDaemon(true);
        thread.start();
    }

    // Prior to this method being invoked all heartbeat messages are ignored/discarded.
    // If startResponding() is invoked on an instance that is already responding then
    // a FailureDectectorException with the message "Already Running" is to be thrown.
    // If any other problem is detected then the FailureDectorException is thrown
    public void startResponding()throws FailureDetectorException {
        if (!handler.isResponding()) {
            log("Start Responding");
            handler.setResponding(true);
        } else {
            throw new FailureDetectorException("Already Running");
        }
    }

    // Once this method returns heartbeat messages will be discraded/ignored until a subsequent
    // startResponding call is made.
    public void stopResponding() {
        log("Stop Responding");
        handler.setResponding(false);
    }

    public static void enableDebugLogging() {
        DEBUG = true;
    }

    private void log(String s) {
        if (DEBUG) {
            System.out.println("["+System.nanoTime()+"][Responder "+laddr+":"+port+"] - "+s);
        }
    }
}
    
