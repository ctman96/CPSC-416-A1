import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
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
    static volatile long seqNum = 0;
    int threshHold;

    private static boolean initialized = false;
    private DatagramSocket socket;
    private Thread handlerThread;
    private MonitorHandler handler;

    private static class PacketData {
        public long received;
        public long epochNonce;
        public long sequenceNum;
        PacketData(long received, long epochNonce, long sequenceNum) {
            this.received = received;
            this.epochNonce = epochNonce;
            this.sequenceNum = sequenceNum;
        }
    }

    // Purely handles receiving packets, putting their data into the given queue
    private static class MonitorReceiver implements Runnable {
        private DatagramSocket socket;

        // Queue into which received packet data is put
        private LinkedBlockingQueue<PacketData> queue;

        MonitorReceiver(DatagramSocket socket, LinkedBlockingQueue<PacketData> queue) {
            this.socket = socket;
            this.queue = queue;
        }

        public void run() {
            while (true) {
                try {
                    byte[] buf = new byte[16]; // TODO: right size?
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    ByteBuffer bb = ByteBuffer.wrap(buf);
                    long received = System.currentTimeMillis();
                    long epochNonce = bb.getLong();
                    long sequenceNum = bb.getLong();
                    System.out.println("Monitor Received: " + epochNonce + ", " + sequenceNum); // TODO debugging - remove
                    PacketData data = new PacketData(received, epochNonce, sequenceNum);
                    queue.put(data);
                } catch (IOException | InterruptedException ex) {
                    System.out.println("Caught exception in MonitorReceiver!!!"); // TODO debugging remove
                    // TODO???
                }
            }
        }
    }

    // Main handler to manage monitoring
    private static class MonitorHandler implements Runnable {
        // Parent reference
        Monitor monitor;

        // Main toggle, if false will discard any received packets and won't send out any heartbeats
        private boolean monitoring = false;

        // Number of consecutive failures required before notifying failure
        private int threshold = 1;

        // Socket
        private DatagramSocket socket;
        private InetAddress raddr;
        private int port;

        // Receiver
        private LinkedBlockingQueue<PacketData> queue = new LinkedBlockingQueue<>();
        private MonitorReceiver receiver;
        private Thread receiverThread;

        private Map<Long, Long> awaitedSeqs = new HashMap<>(); // TODO / DONEish: combine into a dictionary of seqNum->startTime, and remove from dictionary when received. Or after certain amount of time??

        // Track if actively waiting for a response for which seqNum
        private boolean awaitingResponse;
        private long awaitingResponseSeq;

        private long rtt = 3000;

        private int failCount;

        boolean isMonitoring() {
            return this.monitoring;
        }

        void startMonitoring() {
            this.monitoring = true;
        }

        // Stops monitoring and resets necessary fields
        void stopMonitoring() {
            this.monitoring = false;
            this.awaitingResponse = false;
            this.awaitedSeqs.clear();
            this.failCount = 0;
        }

        void setThreshold(int threshold) {
            this.threshold = threshold;
        }

        MonitorHandler(Monitor monitor, DatagramSocket socket, InetAddress raddr, int port) {
            this.monitor = monitor;
            this.socket = socket;
            this.raddr = raddr;
            this.port = port;
            // Create and start the receiver to collect packets
            this.receiver = new MonitorReceiver(socket, queue);
            receiverThread = new Thread(receiver);
            receiverThread.setDaemon(true);
            receiverThread.start();
        }

        public void run() {
            while (true) {
                try {
                    // Check if timed out, and if so increment fails
                    if (awaitingResponse && System.currentTimeMillis() - awaitedSeqs.get(awaitingResponseSeq) > rtt) {
                        System.out.println("Seq " + awaitingResponseSeq + " timed out"); // TODO debugging remove
                        failCount++;
                        awaitingResponse = false;
                    }

                    // Process any received packet data from the queue
                    while (!queue.isEmpty()) {
                        PacketData data = queue.take();
                        // Discard if not monitoring
                        if (!monitoring) continue;
                        // Discard if from different epochNonce
                        if (data.epochNonce != Monitor.eNonce) continue;

                        // If it is a response to the latest heartbeat, register alive and update rtt
                        if (awaitingResponse && data.sequenceNum == awaitingResponseSeq) {
                            System.out.println("Received ack"); // TODO debugging remove
                            // Reset failcount upon receiving a correct ack
                            failCount = 0;
                            awaitingResponse = false;
                        }

                        // Update RTT as average of current RTT and response's RTT
                        if (awaitedSeqs.containsKey(data.sequenceNum)) {
                            System.out.println("Old RTT: " + rtt); // TODO debugging remove
                            long responseRTT = (System.currentTimeMillis() - awaitedSeqs.remove(data.sequenceNum));
                            System.out.println("Res RTT: " + responseRTT); // TODO debugging remove
                            rtt = ( responseRTT + rtt ) / 2;
                            System.out.println("New RTT: " + rtt); // TODO debugging remove
                        }
                    }

                    // Stop here if not monitoring
                    if (!monitoring) continue;

                    // Notify failure if reached failure threshold
                    if (failCount >= threshold) {
                        System.out.println("Failure threshold reached!"); // TODO debugging remove
                        clq.put(monitor);
                        // Stop monitoring
                        this.stopMonitoring();
                    }

                    if (!awaitingResponse) {
                        // Send Heartbeat
                        ByteBuffer bb = ByteBuffer.allocate(16);
                        bb.putLong(Monitor.eNonce);
                        long sequenceNum = Monitor.seqNum++;
                        System.out.println("Monitor Sent: " + Monitor.eNonce + ", " + sequenceNum); // TODO debugging remove
                        bb.putLong(sequenceNum);

                        DatagramPacket HBeat = new DatagramPacket(bb.array(), bb.position(), raddr, port);
                        socket.send(HBeat);
                        awaitingResponse = true;
                        awaitingResponseSeq = sequenceNum;

                        // Add seqNum and time to the list of awaited responses
                        awaitedSeqs.put(awaitingResponseSeq, System.currentTimeMillis());
                    }
                } catch (IOException | InterruptedException ex) {
                    System.out.println("Caught exception in MonitorHandler!!!"); // TODO debugging remove
                    // TODO ??
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

        eNonce = epochNonce;
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
            throw new FailureDetectorException("Security Exception");
        }
        handler = new MonitorHandler(this, socket, raddr, port);
        handlerThread = new Thread(handler);
        handlerThread.setDaemon(true);
        handlerThread.start();
    }

    // Start (or restart) monitoring the remote node using the threshold value.
    // If monitroing is currently in progress then the threshold value is
    // set to this new value. Note: this call does not block.
    public void startMonitoring(int threshold) throws FailureDetectorException {
        if (initialized) {
            handler.setThreshold(threshold);
            handler.startMonitoring();
        } else {
            throw new FailureDetectorException("Monitoring has not been initialized");
        }
    }

    // Stop monitoring the remote node. If the monitoring is currently in progress
    // then nothing is done. Any ACK messages received after this message are to be
    // ignored.
    public void stopMonitoring() {
	    handler.stopMonitoring();
    }

    // Return the user supplied name for this Monitor
    public String getName() {
        return name;
    }


}
    
