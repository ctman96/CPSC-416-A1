import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public class Monitor {
    // Whether to debug log
    private static boolean DEBUG = false;

    private static List<Monitor> monitors = new ArrayList<>();
    // The reserved nonce value;
    public static final long RESERVED_NONCE = -1;

    // The shared "channel" that all monitors use to indicate that a failure has
    // been detected
    static LinkedBlockingQueue<Monitor> clq = new LinkedBlockingQueue<Monitor>();

    // This variable is to be used to hold a string that unqiquelly identifies the Monitor
    String name;

    // epochNonce of current session
    static long eNonce = RESERVED_NONCE;
    // Using AtomicLong for seqNum to be able to get/increment across threads
    static AtomicLong seqNum = new AtomicLong(0);


    // Whether monitoring has been initialized
    private static boolean initialized = false;

    private DatagramSocket socket;

    // Handler
    private Thread handlerThread;
    private MonitorHandler handler;



    // Just wraps received packet data
    private static class PacketData {
        public long epochNonce;
        public long sequenceNum;
        PacketData(long epochNonce, long sequenceNum) {
            this.epochNonce = epochNonce;
            this.sequenceNum = sequenceNum;
        }
    }



    // Purely handles receiving packets, putting their data into the given queue
    private static class MonitorReceiver implements Runnable {
        private Monitor monitor;
        private DatagramSocket socket;

        // Queue into which received packet data is put
        private LinkedBlockingQueue<PacketData> queue;

        MonitorReceiver(Monitor monitor, DatagramSocket socket, LinkedBlockingQueue<PacketData> queue) {
            this.monitor = monitor;
            this.socket = socket;
            this.queue = queue;
        }

        public void run() {
            while (true) {
                try {
                    // Receive packet
                    byte[] buf = new byte[1024];
                    // TODO: do we need to verify?
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    // Parse into longs
                    ByteBuffer bb = ByteBuffer.wrap(buf);
                    long epochNonce = bb.getLong();
                    long sequenceNum = bb.getLong();
                    monitor.log("Receiver received: " + epochNonce + ", " + sequenceNum);
                    // Wrap in PacketData class and add to queue
                    PacketData data = new PacketData(epochNonce, sequenceNum);
                    queue.put(data);
                } catch (IOException | InterruptedException ex) {
                    monitor.log("WARN: Caught exception in MonitorReceiver: " + ex.getMessage());
                    // TODO do anything??
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

        private Map<Long, Long> awaitedSeqs = new HashMap<>(); // TODO remove entry after certain amount of time??? Completely lost packets will currently leak memory

        // Track if actively waiting for a response for which seqNum
        private boolean awaitingResponse;
        private long awaitingResponseSeq;

        private long rtt = 3000;

        private int lostMsgs;

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
            this.lostMsgs = 0;
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
            this.receiver = new MonitorReceiver(monitor, socket, queue);
            receiverThread = new Thread(receiver);
            receiverThread.setDaemon(true);
            receiverThread.start();
        }

        public void run() {
            while (true) {
                try {
                    // Process any received packet data from the queue
                    PacketData data;
                    while ((data = queue.poll()) != null) {
                        monitor.log("Handler received " + data.epochNonce + ", " + data.sequenceNum);

                        // Discard if not monitoring
                        if (!monitoring) {
                            monitor.log("Handler discarded " + data.epochNonce + ", " + data.sequenceNum + " - Not Monitoring");
                            continue;
                        }

                        // Discard if from different epochNonce
                        if (data.epochNonce != Monitor.eNonce) {
                            monitor.log("Handler discarded " + data.epochNonce + ", " + data.sequenceNum + " - Wrong epochNonce");
                            continue;
                        }

                        // If it is a response to the latest heartbeat, register alive and update rtt
                        if (awaitingResponse && data.sequenceNum == awaitingResponseSeq) {
                            monitor.log("Received ACK for current seq");
                            // Reset lostMsgs upon receiving a correct ack
                            lostMsgs = 0;
                            awaitingResponse = false;
                        }

                        // Update RTT as average of current RTT and response's RTT
                        if (awaitedSeqs.containsKey(data.sequenceNum)) {
                            lostMsgs = 0;
                            monitor.log("Received seq "+data.sequenceNum + " was awaited, recalculating RTT");
                            long oldRTT = rtt;
                            long responseRTT = (System.currentTimeMillis() - awaitedSeqs.remove(data.sequenceNum));
                            rtt = ( responseRTT + oldRTT ) / 2;
                            if (rtt <= 0) rtt = 1;
                            monitor.log("Current RTT: " + oldRTT + " , Res RTT: " + responseRTT + " , New RTT: " + rtt);
                        } else {
                            monitor.log(data.epochNonce + ", " + data.sequenceNum + " - Was not an awaited seq");
                        }
                    }

                    // Stop here if not monitoring
                    if (!monitoring) continue;

                    // Check if timed out, and if so increment fails
                    if (awaitingResponse && (System.currentTimeMillis() - awaitedSeqs.get(awaitingResponseSeq)) > rtt) {
                        monitor.log("Seq " + awaitingResponseSeq + " timed out: " + (System.currentTimeMillis() - awaitedSeqs.get(awaitingResponseSeq)) + " vs RTT of " + rtt);
                        lostMsgs++;
                        awaitingResponse = false;
                    }

                    // Notify failure if reached failure threshold
                    if (lostMsgs >= threshold) {
                        monitor.log("Failure threshold reached!");
                        clq.put(monitor);
                        // Stop monitoring
                        this.stopMonitoring();
                        continue;
                    }

                    if (!awaitingResponse) {
                        // Send Heartbeat
                        ByteBuffer bb = ByteBuffer.allocate(16);
                        bb.putLong(Monitor.eNonce);
                        long sequenceNum = Monitor.seqNum.getAndIncrement();
                        monitor.log("Monitor Sent: " + Monitor.eNonce + ", " + sequenceNum);
                        bb.putLong(sequenceNum);

                        DatagramPacket HBeat = new DatagramPacket(bb.array(), bb.position(), raddr, port);
                        socket.send(HBeat);
                        awaitingResponse = true;
                        awaitingResponseSeq = sequenceNum;

                        // Add seqNum and time to the list of awaited responses
                        awaitedSeqs.put(awaitingResponseSeq, System.currentTimeMillis());
                    }
                } catch (IOException | InterruptedException ex) {
                    monitor.log("WARN: Caught exception in MonitorHandler: " + ex.getMessage());
                    // TODO Do anything?
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
        seqNum = new AtomicLong(0);

        if (eNonce == -1) {
            throw new FailureDetectorException("Monitor: Invalid Epoch");
        }

        initialized = true;
        return clq;
    }

    // This class method is to cause all Monitors to stop monitoring their remote
    // node. 
    public static void stopMonitoringAll() {
        monitors.forEach(monitor -> monitor.stopMonitoring());
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
        monitors.add(this);
    }

    // Start (or restart) monitoring the remote node using the threshold value.
    // If monitroing is currently in progress then the threshold value is
    // set to this new value. Note: this call does not block.
    public void startMonitoring(int threshold) throws FailureDetectorException {
        if (initialized) {
            log("Start/Restart Monitoring");
            handler.setThreshold(threshold);
            handler.stopMonitoring();
            handler.startMonitoring();
        } else {
            throw new FailureDetectorException("Monitoring has not been initialized");
        }
    }

    // Stop monitoring the remote node. If the monitoring is currently in progress
    // then nothing is done. Any ACK messages received after this message are to be
    // ignored.
    public void stopMonitoring() {
        log("Stop Monitoring");
	    handler.stopMonitoring();
    }

    // Return the user supplied name for this Monitor
    public String getName() {
        return name;
    }

    public static void enableDebugLogging() {
        DEBUG = true;
    }

    private void log(String s) {
        if (DEBUG) {
            System.out.println("["+System.nanoTime()+"][Monitor "+name+"] - "+s);
        }
    }
}
    
