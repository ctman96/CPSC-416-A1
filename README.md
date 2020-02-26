<h2>Distributed Systems: Assignment 1</h2>
<p style="color: gray;"><small>2019W2</small></p>
<h4 style="color: gray;"><span style="color: #000080;">Updates:</span></h4>
<ul>
<li style="color: gray;"><span style="color: #000080;">January 20 - Updated specification below to indicate that initializeMonitor() must be called before startMonitoring() will return successfully, but that it is okay to be called after a Monitor has been created.</span></li>
<li style="color: gray;">
<span style="color: #000080;">January 15 - Java version. Your code must run on the Linux machines in the student domain. (i.e. thetis.students,cs,ubc,ca, remote.students.cs.ubc.ca etc.) The Java version installed on those machines is:</span>
<ul>
<li style="color: gray;"><span style="color: #000080;">javac -version&nbsp; &nbsp; returns: 11.0.5</span></li>
<li style="color: gray;">
<span style="color: #000080;">java -version returns:&nbsp;</span>
<ul>
<li style="color: gray;"><span style="color: #000080;"> OpenJDK Runtime Environment (build 11.0.5+10-suse-lp151.3.9.2-x8664)</span></li>
<li style="color: gray;"><span style="color: #000080;">OpenJDK 64-Bit Server VM (build 11.0.5+10-suse-lp151.3.9.2-x8664, mixed mode)</span></li>
</ul>
</li>
</ul>
</li>
<li style="color: gray;"><span style="color: #000080;">January 14 - added requirement to complete and handin the Coverpage.txt file. If you created a repo and don't have the Coverpage.txt file. Do a pull, and maybe a merge, and you will get the file.</span></li>
</ul>
<p>In this assignment you will be writing&nbsp; a Java class that can be used to perform failure detection using UDP.</p>
<h3>Overview</h3>
<p>Distributed systems are frequently required to deal with failures. There are many kinds of failures (hardware/software/network/etc). The focus of this assignment is on <i>node failures</i> and you will design a failure detector (FD) for nodes in a distributed system. That is, your FD will be able to tell whether or not a node in a distributed system can no longer be communicated with and is classed as having "failed." In real distributed systems, including those you will build in this course, operate over networks that are asynchronous and unreliable. This makes it impossible to distinguish a network failure from the actual failure of a node.&nbsp;</p>
<p>You will structure your FD as a two Java classes Monitor and Responder that could potentially be re-used across projects. Your FD classes&nbsp; must be able to monitor multiple nodes concurrently, integrate a simple round-trip time estimator, use a simple UDP heartbeat protocol to detect failures, and respond to heartbeats on behalf of the local node. Your FD&nbsp; will be marked automatically (we will write clients and script scenarios that use your class.)&nbsp; It is therefore important to follow the specification below exactly, including the class API and its semantics, the UDP protocol/packet format, and other details.</p>
<h3>High-level protocol description</h3>
<p>In this assignment a distributed system is composed of some number of peer nodes, each of which uses an instances of the FD classes you are implemented. Each node (A node is just program that is running and using your FD)&nbsp; may <i>monitor</i> some subset of nodes and may allow itself to be monitored by other nodes. A monitoring node actively sends <i>heartbeat</i> (AYA) messages (a type of UDP message defined below) to check if the node being monitored has failed, or not. Failure is determined/defined using a policy described below. If a node has been configured to respond to heartbeat messages then upon receiving a heartbeat&nbsp; the node will respond with an ACK (aka IAA). The following diagram illustrates an example three node system and the failure monitoring relationships between the nodes. In the diagram, Node 1 and Node 2 monitor each other (send each other heartbeats and receive acks); Node 2 also monitors Node 3. However, Node 3 does not monitor Node 1 nor Node 2.<br><br><img src="/courses/36019/files/6646997/preview" alt="assign1-topo-example.svg" width="491" height="151" data-api-endpoint="https://canvas.ubc.ca/api/v1/courses/36019/files/6646997" data-api-returntype="File" data-id="6646997" style="max-width: 491px;"></p>
<p>This means that nodes 1 and 2 will each be using the Monitor and Responder classes but Node 3 will only be using the Responder Class.</p>
<h3>Overview of the FD Classes</h3>
<p>The FD has two parts:</p>
<ol>
<li>The Responder,&nbsp; that deals with incoming heartbeat messages from, monitoring nodes</li>
<li>The Monitor which is used to monitor other nodes.</li>
</ol>
<p>These two sets of classes are <strong>independent .&nbsp; </strong>That is they can &nbsp;be used just for responding to heartbeats, or just for monitoring other nodes, or both.</p>
<h4>The Responder</h4>
<p>This class is used to provide the ability for a node to respond to heartbeats. The methods and their use are:</p>
<ul>
<li><span class="s1"><span class="s1"><strong>public Responder(int port, InetAddress laddr)</strong> - the constructor takes the port number&nbsp; and local&nbsp; address to listen on. Since most machines have multiple interfaces, and hence IP addresses,&nbsp; the local address is used to specify which IP address to use. When an instance of a responder is created, it is must not start responding to heartbeat messages until startResponding() had been invoked on the object.&nbsp; More specifically any heartbeat messages targeted at this responder are to be discarded until startResponding() is invoked. Multiple instances of Responders are permitted. If the current local port:local Address pair is&nbsp; in use or if there is any other socket related error at this point then a SocketException is to be thrown.&nbsp;</span></span></li>
<li class="p1">
<span class="s1"><strong> public void startResponding()- </strong>This call t</span>ells an instance f Responder to start responding to heartbeat messages. The responses (ack messages) are to be&nbsp; directed to the source IP and port number of the corresponding heartbeat message. If startResponding() is invoked on an instance that is already responding then a <span class="s1">FailureDectectorException with the message "Already Running" is to be thrown.</span>
</li>
<li>
<strong>stopResponding</strong>() - This Responder instance&nbsp; is to stop responding to heartbeat messages. Any heartbeat messages received after an invocation of this call has returned are to be discarded. If the Responder currently isn't responding to heartbeat messages then no action is taken.&nbsp;</li>
</ul>
<h4>The Monitor</h4>
<ul>
<li>
<strong>public static LinkedBlockingQueue&lt;Monitor&gt; initializeMonitor(long epochNonce) </strong><strong>&nbsp;</strong>This class method is used to initialize the epoch value to be used across all Monitors.&nbsp; If this method&nbsp; is called more than once then a FailureDetectorException with the msg "Monitor:&nbsp; Already Initialized" is to be thrown.&nbsp; The epoch value of -1 is reserved and cannot be used. If the epochNonce is -1 then a FailureDetectorException with the msg "Monitor: Invalid Epoch" is to be thrown The value returned is a thread safe data structure used by all the Monitors to communicate which nodes are no longer being monitored because a failure has been detected.&nbsp; More on the use of this later. This method does not have to be called before a Monitor() is created, but it must be called before startMonitoring() will return successfully.</li>
<li>
<strong>public Monitor(InetAddress laddr, int lport, InetAddress raddr, int port,<br>&nbsp; &nbsp; &nbsp; &nbsp; &nbsp; &nbsp; &nbsp; &nbsp; &nbsp; &nbsp; &nbsp; &nbsp; &nbsp; &nbsp; &nbsp; &nbsp; &nbsp; &nbsp;String u_name) )</strong> <br>The constructor creates an object that, upon invoking <strong>startMonitoring()</strong> will start monitoring a node (sending heartbeats) using the ports and IP addresses held provided as parameters. For a more detailed description of the parameters see the starter code.&nbsp; &nbsp;If the local IP address or port are in use or there are any other socket related errors a SocketException is thrown. Any other errors are to result in a FailureDetectorException being thrown.&nbsp;</li>
<li>
<strong>public startMonitoring(int threshold)<br></strong>This method tells the object&nbsp; to&nbsp; start monitoring a node (sending heartbeats) using the ports and IP addresses provided to the constructor.&nbsp; &nbsp;The Threshold value&nbsp; specifies the number of consecutive and un-acked heartbeats messages that have to be detected before triggering a failure notification. See more details in the starter code.&nbsp; If a call to InitializeMonitor() has not been made when startMonitoring() is called then this method will throw a FailureDetectorException exception with an appropriate text value.</li>
<li>
<strong>public stopMonitoring</strong>() This tells the object to stop monitoring (sending heartbeats to) the node assigned to&nbsp; this object.&nbsp; This call always succeeds even if the object currently isn't monitoring a node.</li>
<li>
<strong>public</strong> <strong>stopMonitoringAll</strong>()&nbsp; This class method causes all monitoring (sending heartbeats) to be stopped. This call always succeeds.</li>
</ul>
<p><strong>Notification semantics:</strong></p>
<ul>
<li>All failure notifications must be delivered on the queue (which will be called the notify-channel) returned by the initializeMonitor() method.&nbsp;&nbsp;</li>
<li>Notifying the application must not block the monitoring or responding to heartbeats. That is, the notification must be passed asynchronously on the notify-channel. You can assume the notify-channel size is sufficient to prevent blocking of a Monitor instance.</li>
<li>If a node Y was detected as failed, then
<ol>
<li>failure notification must be generated by adding a reference to the Monitor object that detected the failure to the notify channel that was returned by initializeMonitor().&nbsp;&nbsp;</li>
<li>the&nbsp; monitoring of node Y&nbsp; must stop . To re-start the monitoring of node Y after it was detected as failed, the client must again invoke startMonitoring()</li>
</ol>
</li>
<li>No failure notification of node Y must be generated after the invocation of stopMonitoring() has returned.</li>
</ul>
<h4>The protocol on the wire</h4>
<p>For interoperability purposes the heartbeat and ACK messages in the system must have a specific format:</p>
<pre>type HBeatMessage struct {
	EpochNonce int64 // Identifies this fdlib instance/epoch.
	SeqNum     int64 // Unique for each heartbeat in an epoch.
}

type AckMessage struct {
	HBEatEpochNonce int64 // Copy of what was received in the heartbeat.
	HBEatSeqNum     int64 // Copy of what was received in the heartbeat.
}
</pre>
<p>Basically this is just two 64 bit integer values one after the other on the wire. The first 64 bits will be the EpochNonce, and the 2nd 64 bits the SeqNum.</p>
<ul>
<li>EpochNonce is used to distinguish different instances, over time, of the different Monitors that might be running.&nbsp; On starting the&nbsp; system will select a unique EpochNonce and tell the system what it is with a call to initializeMonitor().&nbsp; The system will&nbsp; ignore all acks that it receives that do not reference the latest EpochNonce.</li>
<li>The SeqNum is an identifier that is an arbitrary number which uniquely identifies the heartbeat in an epoch. This number could start at any value.</li>
<li>HBEatEpochNonce and HBEatSeqNum in the AckMessage simply identify the HBeatMessage that the ack corresponds to.</li>
</ul>
<p>The diagram below is a "time-space" diagram (time flows down) that illustrates how a Monitor at node X would detect the failure of node Y (by not observing acks from node Y):</p>
<p><img src="/courses/36019/files/6687202/preview" alt="assign1_FDProtocol.svg" width="300" height="345" data-api-endpoint="https://canvas.ubc.ca/api/v1/courses/36019/files/6687202" data-api-returntype="File" data-id="6687202" style="max-width: 300px;"><br><br>Note how the Monitor resends the heartbeat message (after an RTT timeout) exactly three times (based on the lost-msgs-thresh value of 3 passed to startMonitoring). After three heartbeat messages have <strong>all</strong> timed-out, node Y is considered failed and a failure notification is put on the notification channel. Your Monitor class must implement this behaviour precisely.</p>
<p>In general, your timeout mechanism should behave as follows:</p>
<ul>
<li>If an ack message is not received in the appropriate RTT timeout interval, then the count of lost msgs should be incremented by 1.</li>
<li>When an ack message is received (even if it arrives after the RTT timeout), the count of lost msgs must be reset to 0&nbsp;</li>
<li>ACKs that arrive after a failure notification has been generated or the stopMonitoring() method has been completed must be ignored.</li>
</ul>
<h3>Round-trip time (RTT) estimation</h3>
<p>Your library must wait for a monitored node to reply to a heartbeat with an ack (this is basically a stop-and-wait protocol). This means that there should, if the roundtrip time is properly estimated,&nbsp; be one heartbeat message in the network from a monitoring node to a monitored node at a time.&nbsp; If&nbsp; an ACK is not received in an&nbsp; RTT time,&nbsp; then another heartbeat should be sent.&nbsp; How long should a reply be waited for? Depending on where the node is physically located, the wait time will have to vary. You must implement a simple RTT estimator to customize the waiting time for each node being monitored. Note that this waiting time may vary for different heartbeat messages.</p>
<p>Your RTT estimator should work as follows:</p>
<ul>
<li>Use a value of 3 seconds as the initial RTT value for a previously un-monitored node.</li>
<li>Each time an ack is received the object will
<ol>
<li>compute the RTT based on when the heartbeat was sent, and&nbsp;</li>
<li>update the RTT for the node to be the average of the last RTT value for this node and the computed RTT value in (1). Note that the same calculation must be performed even if the ack message arrives after the RTT timeout (but only if it arrives before a failure notification is generated)&nbsp; This has implications for what information needs to be remembered when a heartbeat messages is sent and for how long it needs to be remembered.</li>
</ol>
</li>
<li>The system must remember and use the last RTT value computed for a node if monitoring of that node is stopped, either explicitly or a result of a failure, and then restarted.</li>
</ul>
<p>If the Monitor receives an ACK&nbsp; before the RTT timeout, then when should it send the next heartbeat? Your system must send the next heartbeat message no longer than RTT time since the heartbeat that the received ack is acknowledging. That is, if the first heartbeat was sent at time 1 with RTT of 5, and an ack arrived at time 2. Then, the second heartbeat must be sent after the ack is received but before time 6. You must not use the arrival of an ack to automatically trigger the sending of a heartbeat message.&nbsp;</p>
<h3>Assumptions you can make</h3>
<ul>
<li>You can assume that all messages fit into 1024 bytes.</li>
<li>Nodes will not monitor themselves, although a good implementation should allow it.</li>
</ul>
<h3>Assumptions you cannot make</h3>
<ul>
<li>UDP is reliable.</li>
<li>Round-trip time between nodes is predictable or bounded.</li>
</ul>
<h3>Implementation requirements</h3>
<ul>
<li>The client code must compile and be able to run on the CS Linux machines</li>
<li>Your code must not assume that UDP is reliable.</li>
<li>The format of the messages on the wire must be as described.</li>
<li>Your solution can only use standard Java Libraries.&nbsp;</li>
<li>You must hand in the completed Coverpage.txt file.</li>
</ul>
<h3>Solution spec</h3>
<p>Complete the two classes Responder and Monitor. You cannot change any of the other classes that have been provided nor can you change or add any public APIs of the Responder or Monitor classes.&nbsp; However, you may add additional private methods or private classes provided no new java files are required. Our marking scripts will rely on this API to automatically grade your solution.</p>
<h4>Starter code and testing servers</h4>
<p>Follow the instructions <a title="Assignment Policies and Procedures" href="/courses/36019/pages/assignment-policies-and-procedures" data-api-endpoint="https://canvas.ubc.ca/api/v1/courses/36019/pages/assignment-policies-and-procedures" data-api-returntype="Page">here</a> to get your repo and to review the instructions that apply to assignments in this course.&nbsp; A simple tester.java program is provided to give you a sense of what you need to do.&nbsp;</p>
<p>We will release a testing server, details will be provided later,&nbsp; that will allow some simple testing but you need to focus on developing various test cases. You might want to try getting your implementation to work with someone else's in the class.</p>
<h3>Rough grading scheme</h3>
<p><span style="color: ff0000;"><strong>Your code must compile and work on the department linux servers.&nbsp; We will be importing your implementations of the Responder and Monitor classes into our testing main program.&nbsp; Consequently if you do not adhere to the provided API it won't compile and run and you will get 0.&nbsp; </strong></span><span style="color: ff0000;"><strong>This is true regardless of how many characters had to be changed to make your solution compile, and regardless of how well your solution works with a different API or on a different machine.</strong></span> The high-level A1 mark breakdown looks like this:</p>
<ul>
<li>30% : StartResponding and StopResponding work</li>
<li>70% : AddMonitor/RemoveMonitor/StopMonitoring work</li>
</ul>
<h3>Advice</h3>
<ul>
<li>Have the SeqNum start at 0 and increment by 1. This will help with debugging.</li>
<li>Develop some unit tests whose failure will tell you when you've broken something that was working.</li>
<li>Compile and run your code on the student domain&nbsp; servers.</li>
<li>Most likely you will need to have one thread associated with each instance of a Responder or Monitor object.&nbsp;</li>
<li>You might also want to consider writing an interactive client program that invokes different methods on the based on the commands entered (e.g., If a user types "start X", then&nbsp; start monitoring X ). It will help you experiment with different scenarios without having to write multiple sample clients.</li>
<li>Note that due to NATs and Firewalls you may be unable to reach your clients running on department servers from outside of the UBC network. Test by running all code on department&nbsp; nodes.</li>
<li>If you implement things properly you can probably have a node monitor itself which might make some of the initial debugging easier.</li>
<li>On most networks these days the loss of a packet is rare.&nbsp; As a result you might want to provide a mechanism whereby&nbsp; packets that arrive are discarded with a certain probability while you are testing things.&nbsp;</li>
</ul>
<p>The <a title="A1" href="/courses/36019/modules/235108" data-api-endpoint="https://canvas.ubc.ca/api/v1/courses/36019/modules/235108" data-api-returntype="Module">A1 Module</a> provides the links and instructions on getting the starter code and how code is handed in.</p></div>
