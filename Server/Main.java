import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.io.Serializable;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
/*
 * Add bandwidth controls channel for each path separately and utilize them to probe the instantaneous bandwidth. 
 * Use mean or any other smoothening function to dampen the noisy spikes.
 */
class BWControlChannel implements Runnable {

    @Override
    public void run() {
        // Implement the bandwidth control channel here
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


class Status {
    // Class to log status of current data transfer
    public boolean status;
    public Status(boolean status){
        this.status = status;
    }
}

class RTTHandler implements Runnable {
    // This class handles the RTT control channel
    int serverPort;
    private InetAddress clientIP;
    private int clientPort;
    int timeout;
    int id;
    int bufSize = 20;
    int timestampSendingInternal;
    float rttEstimate = 0.0F;
    Status status;
    public RTTHandler(int serverPort, int timeout, int id, int timestampSendingInternal, Status status) {
        this.serverPort = serverPort;
        this.timeout = timeout;
        this.id = id;
        this.timestampSendingInternal = timestampSendingInternal; // in ms
        this.status = status;
    }
    public void updateEstimate(float newValue){
        this.rttEstimate = ((1 - 0.125F) * this.rttEstimate) + (0.125F * newValue);
    }
    public void connect() throws SocketException, UnknownHostException, InterruptedException {
        // Receive the "Hi" message from the client to get its IP and Port
        DatagramSocket socket = new DatagramSocket(this.serverPort);

        byte[] buf = new byte[this.bufSize];
        DatagramPacket dpSend;
        DatagramPacket dpReceive;

        // Getting Hi message
        try {
            dpReceive = new DatagramPacket(buf, buf.length);
            socket.receive(dpReceive);
            System.out.println(this.id + " Hi Received");

            // Get client IP and Port
            clientIP = dpReceive.getAddress();
            clientPort = dpReceive.getPort();
//            System.out.println("Client IP: " + clientIP);
        } catch (
                IOException e) {
            throw new RuntimeException(e);
        }

        while (true) {
            // Sending timestamps
            long sentTimestamp = System.currentTimeMillis();
            long recvTimestamp;
            buf = String.valueOf(sentTimestamp).getBytes();
            byte[] recvBuf = new byte[buf.length];
            try {
                dpSend = new DatagramPacket(buf, buf.length, this.clientIP, this.clientPort);
                socket.send(dpSend);

                dpReceive = new DatagramPacket(recvBuf, recvBuf.length);
                socket.setSoTimeout(this.timeout);
                socket.receive(dpReceive);
                recvTimestamp = Long.parseLong(new String(recvBuf));
                long currentTimeStamp = System.currentTimeMillis();
                float rtt = currentTimeStamp - recvTimestamp;
                if (rtt == 0){
                    rtt = 1.0F;
                }
                System.out.println(this.id + " RTT: " + rtt + " " + recvTimestamp + " " + sentTimestamp);

                this.updateEstimate(rtt);
                if (this.status.status) {
                    System.out.println("Stopping Control Channels");
                    break;
                }

                // Sleep before next send
                Thread.sleep(this.timestampSendingInternal);

            } catch (SocketTimeoutException e) {
                System.out.println(this.id + " Resending Timestamp");
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
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}

class Server{
    private ServerSocket serverSocketPath1;
    private ServerSocket serverSocketPath2;
    private Socket connectedSocketPath1;
    private Socket connectedSocketPath2;
    int sendBufferSize = 1435;
    RTTHandler rttHandlerPath1; RTTHandler rttHandlerPath2;
    private String videoFileName = "video/CN_Lect_NPTEL.mp4";

    public Server(int path1Port, int path2Port, RTTHandler rttHandlerPath1, RTTHandler rttHandlerPath2) throws IOException {
        this.serverSocketPath1 = new ServerSocket(path1Port);
        this.serverSocketPath2 = new ServerSocket(path2Port);

        this.connectedSocketPath1 = serverSocketPath1.accept();
        System.out.println("Connected to port through Path1: " + path1Port);

        this.connectedSocketPath2 = serverSocketPath2.accept();
        System.out.println("Connected to port through Path2: " + path2Port);

        this.rttHandlerPath1 = rttHandlerPath1;
        this.rttHandlerPath2 = rttHandlerPath2;
    }

    public byte[] readFile(String filename) throws IOException {
        /*
        This function reads the whole video file,
        and returns the content as a byte array.
        */
        File file = new File(filename);
        if (file.exists()) {
            FileInputStream fl = new FileInputStream(file);
            byte[] arr = new byte[(int) file.length()];
            int res = fl.read(arr);
            if (res < 0) {
                System.out.println("Error in reading file");
                fl.close();
                return null;
            }
            fl.close();
            return arr;
        } else {
            return null;
        }
    }

    public boolean sendFile() throws IOException, InterruptedException {
        /*
        Sends the video file to the client
        */
        Thread.sleep(2000); // wait for cold start
        Packet packet;
        byte[] fileBytes = readFile(this.videoFileName);
        int step = this.sendBufferSize;
        int sentSliceSize = 0;
        boolean isBreak = false;
        ByteBuffer byteBuffer;


        int seqNumber = 0;

        // Reading 1024 bytes from the read file and sending it over TCP socket
        while (!isBreak) {
            byte[] slice;
            // Bifurcating the whole file content into chunks
            if (sentSliceSize + this.sendBufferSize >= fileBytes.length) { // last chunk
                slice = Arrays.copyOfRange(fileBytes, sentSliceSize, fileBytes.length);
                isBreak = true;
            } else {
                slice = Arrays.copyOfRange(fileBytes, sentSliceSize, step);
                sentSliceSize = step;
                step += this.sendBufferSize;
            }
            packet = new Packet(seqNumber, slice);

            // Attaching timestamp to the chunks
            byte[] currentTime = String.valueOf(System.currentTimeMillis()).getBytes();

           /*  byteBuffer = ByteBuffer.allocate(sendBufferSize + currentTime.length);
            byteBuffer.put(currentTime);
            byteBuffer.put(slice);
            byteBuffer.flip(); */

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            ObjectOutputStream objectOutputStream = new ObjectOutputStream(outputStream);
            objectOutputStream.writeObject(packet);
            objectOutputStream.close();

            byte[] packetBytes = outputStream.toByteArray();

            try {
                if (this.rttHandlerPath1.rttEstimate <= this.rttHandlerPath2.rttEstimate) {
                    connectedSocketPath1.getOutputStream().write(packetBytes);
                    System.out.println("Sent over Path 0");
                } else {
                    connectedSocketPath2.getOutputStream().write(packetBytes);
                    System.out.println("Sent over Path 1");
                }
                seqNumber++;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        connectedSocketPath1.close(); connectedSocketPath2.close();
        serverSocketPath1.close(); serverSocketPath2.close();
        return true;
    }
}

public class Main {
    public static void main(String[] args) throws IOException, InterruptedException {
        int portForPath1 = 6001;
        int portForPath2 = 6002;
        int rttHandlerPortPath1 = 8001;
        int rttHandlerPortPath2 = 8002;
        int timeoutOfRttHi = 200;
        Status status = new Status(false);

        // For RTT Channel
        RTTHandler rttHandlerPath1 = new RTTHandler(rttHandlerPortPath1, timeoutOfRttHi, 0, timeoutOfRttHi, status);
        RTTHandler rttHandlerPath2 = new RTTHandler(rttHandlerPortPath2, timeoutOfRttHi, 1, timeoutOfRttHi, status);

        Thread rttPath1Thread = new Thread(rttHandlerPath1);
        Thread rttPath2Thread = new Thread(rttHandlerPath2);

        rttPath1Thread.start();
        rttPath2Thread.start();

        // Sending the video file
        Server server = new Server(portForPath1, portForPath2, rttHandlerPath1, rttHandlerPath2);

        long t1 = System.currentTimeMillis();
        status.status = server.sendFile();
        long t2 = System.currentTimeMillis();
        System.out.println("Total Sending Time: " + (t2 - t1));

        rttPath1Thread.join();
        rttPath2Thread.join();

    }
}
