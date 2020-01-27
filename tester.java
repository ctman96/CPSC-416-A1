import java.net.InetAddress;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class tester {

    public static void main(String[] args) throws Exception {


	// create a responder and then start responding.
	Responder rsp = new Responder(6666, InetAddress.getByName("localhost"));
	rsp.startResponding();

	// Initialize and Create Monitor to send heartbeat messages
	// Observe that we are going to monitor ourselves.
	LinkedBlockingQueue<Monitor> fdQueue = Monitor.initializeMonitor(2020);
	Monitor mon = new Monitor(InetAddress.getByName("127.0.0.1"), 4567,
			      InetAddress.getByName("localhost"), 6666,
			      "TestMonitor of localhost:" + 6666);
	// startMonitoring with a threshold of 6
	mon.startMonitoring(6);

	//wait a maximum of 10 seconds for a failure
	fdQueue.poll(10, TimeUnit.SECONDS);
	System.out.println("The monitor's name is " + mon.getName());

	// calls to stop responding
	rsp.stopResponding();
	// If things are working we would now get a timeout experience
	mon.startMonitoring(2);
	fdQueue.poll(10, TimeUnit.SECONDS);
	Monitor.stopMonitoringAll();
    }
}
