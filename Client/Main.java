import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.io.Serializable;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectInputStream;
import java.util.PriorityQueue;
import java.util.Comparator;


class Status{
    // Class to log status of current data transfer
    public boolean status;
    public Status(boolean status){
        this.status = status;
    }
}


class Packet implements Serializable {
    private int seqNum;
    private byte[] data;

    public Packet(int seqNum, byte[] data) {
        this.seqNum = seqNum;
        this.data = data;
    }

    public int getSeqNum() {
        return seqNum;
    }

    public byte[] getData() {
        return data;
    }
}

class RTTHandler implements Runnable {
    // This class handles the RTT control channel
    String serverIp;
    int serverPort;
    int timeout;
    int id;
    int recvBufSize;
    Status status;

    RTTHandler(String serverIp, int serverPort, int timeout, int id, Status status) throws IOException {
        this.serverIp = serverIp;
        this.serverPort = serverPort;
        this.timeout = timeout;
        this.id = id;
        this.status = status;
    }

    public void connect() throws SocketException, UnknownHostException {
        // Send a "Hi" message to the server to create a separate thread for the client
        DatagramSocket socket = new DatagramSocket();
        InetAddress ip_server = InetAddress.getByName(this.serverIp);

        recvBufSize = 20;
        byte[] hiBuf = "Hi".getBytes();
        byte[] recvBuf = new byte[recvBufSize];
        DatagramPacket dpSend = new DatagramPacket(hiBuf, hiBuf.length, ip_server, this.serverPort);
        DatagramPacket dpReceive;
        boolean doneHandshake = true;
        try {
            while(doneHandshake){
                socket.send(dpSend);
                System.out.println(this.id + " Hi Sent");

                dpReceive = new DatagramPacket(recvBuf, recvBuf.length);
                socket.setSoTimeout(this.timeout);
                try{
                    socket.receive(dpReceive);
                    doneHandshake = false;
                    recvBufSize = dpReceive.getLength();
//                    System.out.println(this.id + " received bytes " + dpReceive.getLength() + " " + new String(recvBuf) + " Received HiAck");
                    System.out.println(this.id + " Received HiAck");
                } catch (SocketTimeoutException e){
                    System.out.println(this.id + " Resending Hi");
                }
            }
        } catch (
                IOException e) {
            throw new RuntimeException(e);
        }

        // Receiving timestamps for RTT estimation
        while (true) {
            recvBuf = new byte[recvBufSize];
            dpReceive = new DatagramPacket(recvBuf, recvBuf.length);
            socket.setSoTimeout(0);

            try {
                socket.receive(dpReceive);
//                long recvTimestamp = Long.parseLong(new String(recvBuf));
//                System.out.println(this.id + " Received timestamp: " + recvTimestamp);

                dpSend = new DatagramPacket(recvBuf, recvBuf.length, ip_server, this.serverPort);
                socket.send(dpSend);
                if (this.status.status){
                    System.out.println("Stopping controls channels");
                    break;
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public void run() {
        try {
            connect();
        } catch (SocketException e) {
            throw new RuntimeException(e);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
    }
}

class Client implements Runnable{
    private Socket connectedSocket;
    int recvBufferSize = 1448;
    int id;
    Status status;
    private String videoFileName = "video/CN_Lect_NPTEL.zip";

    public Client(String serverIP, int portToConnect, int id, Status status) throws IOException {
        this.connectedSocket = new Socket(serverIP, portToConnect);
        System.out.println("Connected : " + id);
        this.id = id;
        this.status = status;
    }

    public void receiveFile() throws IOException {
        ByteArrayOutputStream fileBytes = new ByteArrayOutputStream();
        PriorityQueue<Packet> packetQueue = new PriorityQueue<>(new PacketComparator());
        int expectedSequenceNumber = 0;
    
        while (true) {
            byte[] chunk = new byte[this.recvBufferSize];
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            int len;
            while ((len = this.connectedSocket.getInputStream().read(chunk)) != -1) {
                baos.write(chunk, 0, len);
                if (len < this.recvBufferSize) {
                    break;
                }
            }
            byte[] objectBytes = baos.toByteArray();
            
            if (objectBytes.length == 0) {
                try (OutputStream outputStream = new FileOutputStream(this.videoFileName)) {
                    fileBytes.writeTo(outputStream);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                fileBytes.reset();
                this.connectedSocket.close();
                this.status.status = true;
                break;
            } else {
                // Deserialize the byte array into a Packet object
                ByteArrayInputStream bais = new ByteArrayInputStream(objectBytes);
                Packet packet = null;
                try (ObjectInputStream ois = new ObjectInputStream(bais)) {
                    packet = (Packet) ois.readObject();
                } catch (ClassNotFoundException e) {
                    e.printStackTrace();
                }
    
                if (packet != null) {
                    packetQueue.add(packet);
    
                    while (!packetQueue.isEmpty() && packetQueue.peek().getSeqNum() == expectedSequenceNumber) {
                        Packet nextPacket = packetQueue.poll();
                        fileBytes.write(nextPacket.getData());
                        expectedSequenceNumber++;
                    }
                    System.out.println("Received " + objectBytes.length + " Bytes with seqNum: " + expectedSequenceNumber);
                }
               
            }
        }
    }

    @Override
    public void run() {
        try {
            receiveFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}

public class Main {
    public static void main(String[] args) throws IOException, InterruptedException {
        // Clears old files
        Process p = Runtime.getRuntime().exec("bash clear.sh");
        p.waitFor();

        Status done = new Status(false);
        int portForPath1 = 6001; int portForPath2 = 6002;
        int rttHandlerPortPath1 = 8001;
        int rttHandlerPortPath2 = 8002;
        int timeoutOfRttHi = 200;
        String serverIP = "192.168.45.184"; // wifi as the server
        String helperIP = "192.168.242.184"; // ethernet as the helper

        Client client1 = new Client(serverIP, portForPath1, 0, done);
        Client client2 = new Client(helperIP, portForPath2, 1, done);

        // For RTT Control Channels
        RTTHandler rttHandlerPath1 = new RTTHandler(serverIP, rttHandlerPortPath1, timeoutOfRttHi, 0, done);
        RTTHandler rttHandlerPath2 = new RTTHandler(helperIP, rttHandlerPortPath2, timeoutOfRttHi, 1, done);

        Thread rttPath1Thread = new Thread(rttHandlerPath1);
        Thread rttPath2Thread = new Thread(rttHandlerPath2);

        rttPath1Thread.start();
        rttPath2Thread.start();

        // For Video Channels
        Thread client1Thread = new Thread(client1);
        Thread client2Thread = new Thread(client2);

        client1Thread.start();
        client2Thread.start();

        client1Thread.join();
        client2Thread.join();
        rttPath1Thread.join();
        rttPath2Thread.join();
    }
}
