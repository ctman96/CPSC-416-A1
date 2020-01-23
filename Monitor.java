import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.concurrent.LinkedBlockingQueue;

public class Monitor {
    // The reserved nonce value;
    public static final long RESERVED_NONCE = -1;

    // The shared "channel" that all monitors use to indicate that a failure has
    // been detected
    static LinkedBlockingQueue<Monitor> clq = new LinkedBlockingQueue<Monitor>();

    // This variable is to be used to hold a string that unqiquelly identifies the Monitor
    String name;
    
    static long eNonce = RESERVED_NONCE;
    static long seqNum = 0;
    int threshHold;

    private static boolean initialized = false;
    private DatagramSocket socket;
    private Thread thread;
    private MonitorHandler handler;

    // Runnable handler to
    private static class MonitorHandler implements Runnable {
        private boolean monitoring = false;
        private int threshold = 1;
        private DatagramSocket socket;
        private InetAddress raddr;
        private int port;

        private boolean awaitingResponse;
        private long awaitingResponseSeq;

        private int failCount;

        boolean isMonitoring() {
            return monitoring;
        }

        void setMonitoring(boolean responding) {
            this.monitoring = responding;
        }

        void setThreshold(int threshold) {
            this.threshold = threshold;
        }

        MonitorHandler(DatagramSocket socket, InetAddress raddr, int port) {
            this.socket = socket;
            this.raddr = raddr;
            this.port = port;
        }

        public void run() {
            while (true) {
                // TODO: if not awaiting response:
                if (!monitoring) continue;

                try {
                    if (awaitingResponse) {
                        byte[] buf = new byte[16]; // TODO: right size?
                        DatagramPacket packet = new DatagramPacket(buf, buf.length);
                        try {
                            socket.receive(packet);

                            ByteBuffer bb = ByteBuffer.wrap(buf);
                            long epochNonce = bb.getLong();
                            long seqNum = bb.getLong();

                            System.out.println("Monitor Received: " + epochNonce + ", " + seqNum); // TODO debugging - remove

                            if (epochNonce == Monitor.eNonce && seqNum == awaitingResponseSeq) {
                                // Reset failcount upon receiving a correct ack
                                failCount = 0;
                                awaitingResponse = false;
                            }
                        } catch (SocketTimeoutException ex) {
                            failCount++;
                            // TODO timeout
                        }
                        if (failCount > threshold) { // TODO: is this right?
                            // TODO notify failure
                        }
                        // TODO: track time awaiting response? Otherwise receiving any extraneous packets will reset the timeout.
                    } else {
                        // Send Heartbeat
                        ByteBuffer bb = ByteBuffer.allocate(16);
                        bb.putLong(Monitor.eNonce);
                        long seqNum = Monitor.seqNum++;
                        bb.putLong(seqNum);

                        DatagramPacket HBeat = new DatagramPacket(bb.array(), bb.position(), raddr, port);
                        socket.send(HBeat);
                        awaitingResponse = true;
                        awaitingResponseSeq = seqNum;
                    }
                } catch (IOException ex) {
                    // TODO
                }
            }
        }
    }

    // This class method is used to set the epoch nonce, which is the value of the
    // epochNonce parameter. The return value is the channel the caller can
    // monitor to be notified that a failure has been detected.  If this method
    // is called more than once then a FailureDetectorException with the msg
    // "Monitor:  Already Initialized" is to be thrown.  The epoch value of -1 is
    // reserved and cannot be used. If the epochNonce is -1 then a
    // FailureDetectorException with the msg "Monitor: Invalid Epoch" is to be thrown.
    public static LinkedBlockingQueue<Monitor> initializeMonitor(long epochNonce) throws FailureDetectorException {
        if (initialized) {
            throw new FailureDetectorException("Monitor: Already Initialized");
        }

        eNonce = System.currentTimeMillis();
        seqNum = 0;

        if (eNonce == -1) {
            throw new FailureDetectorException("Monitor: Invalid Epoch");
        }

        initialized = true;
        return clq;
    }

    // This class method is to cause all Monitors to stop monitoring their remote
    // node. 
    public static void stopMonitoringAll() {
        // TODO
     	System.out.println("stopMonitoringAll needs to be implemented");
    }

    // This constructor sets up a Monitor, but it does not start sending heartbeat
    // messages until the startMonitoring method is invoked.
    //  laddr - the address of the local interface to send UDP packets from and
    //          receive UDP packets on
    //  lport - the local port to use when sending heartbeat messages and receiving the
    //          acks
    //  raddr - the remote IP address to send the heartbeat messages to
    //  rport - the remote port number to sent the heartbeat messages to
    //  u_name - This sting is to be human readable value that the caller can
    //           use to provide a printable name for this Monitor.
    //
    // If the laddr:lport combination identify a port that cannot be opened
    // for the sending of UDP packets then a SocketException is to be thrown.
    // If any other type of error is detected that would prevent the operation of
    // the monitor then a FailureDetectionException, with a useful name (i.e.
    // the String parameter) is to be thrown.
    public Monitor(InetAddress laddr, int lport, InetAddress raddr, int port, String u_name)
            throws FailureDetectorException, SocketException {
        name = u_name;
        try {
            socket = new DatagramSocket(lport, laddr);
        } catch (SecurityException ex) {
            throw new FailureDetectorException("Security Exception"); // TODO?
        }
        handler = new MonitorHandler(socket, raddr, port); // TODO: here or in startMonitoring
        thread = new Thread(handler);
        thread.setDaemon(true);
        thread.start();
    }

    // Start (or restart) monitoring the remote node using the threshold value.
    // If monitroing is currently in progress then the threshold value is
    // set to this new value. Note: this call does not block.
    public void startMonitoring(int threshold) throws FailureDetectorException {
        if (initialized) {
            handler.setThreshold(threshold);
            handler.setMonitoring(true);
        } else {
            throw new FailureDetectorException("Monitoring has not been initialized");
        }
    }

    // Stop monitoring the remote node. If the monitoring is currently in progress
    // then nothing is done. Any ACK messages received after this message are to be
    // ignored.
    public void stopMonitoring() {
	    handler.setMonitoring(false);
    }

    // Return the user supplied name for this Monitor
    public String getName() {
        return name;
    }


}
    
