package com.didlink;

import com.google.common.base.Preconditions;
import io.vos.stun.attribute.*;
import io.vos.stun.demo.EstablishListener;
import io.vos.stun.message.Message;
import io.vos.stun.protocol.Agent;
import io.vos.stun.protocol.ResponseHandler;
import io.vos.stun.util.Bytes;

import java.io.IOException;
import java.net.*;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static io.vos.stun.message.Messages.MESSAGE_CLASS_REQUEST;
import static io.vos.stun.message.Messages.MESSAGE_METHOD_PP;

public class UdpPpClient {
//  Timer timer;
//  String stunServer = "www.disneyfans.cn";
//  int port = 3478;
  int timeout = 500; //ms

  public void tryTest(DatagramSocket datagramSocket,
                      String stunServer,
                      int stunPort,
                      long uid,
                      double latitude,
                      double longitude,
                      long locatetime,
                      EstablishListener udpEstablishedListener) {
    ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);

    DatagramClient task = new DatagramClient(datagramSocket,
            stunServer,
            stunPort,
            uid,
            latitude,
            longitude,
            locatetime,
            timeout,
            udpEstablishedListener);

    executor.schedule(task, 10, TimeUnit.MILLISECONDS);
    executor.shutdown();
  }

  private class FollowTask extends TimerTask {
    private EstablishListener udpEstablishedListener;
    String publicAddress;
    int publicPort;
    int localPort;

    public FollowTask(String publicAddress,
                      int publicPort,
                      int localPort,
                      EstablishListener udpEstablishedListener) {
      super();
      this.publicAddress = publicAddress;
      this.publicPort = publicPort;
      this.localPort = localPort;
      this.udpEstablishedListener = udpEstablishedListener;
    }

    public void run() {
      if (this.udpEstablishedListener != null)
        udpEstablishedListener.established(publicAddress, publicPort, localPort);
    }
  }

  private class ErrorNotify extends TimerTask {
    private EstablishListener udpEstablishedListener;

    public ErrorNotify(EstablishListener udpEstablishedListener) {
      super();
      this.udpEstablishedListener = udpEstablishedListener;
    }

    public void run() {
      if (this.udpEstablishedListener != null)
        udpEstablishedListener.onError();
    }
  }

  private class DatagramClient extends TimerTask {

    private DatagramSocket dgramSocket;
    private String stunServer;
    private int serverPort;
    private int timeout;
    private final Agent agent;
    private EstablishListener udpEstablishedListener;

    private long uid;
    private double latitude;
    private double longitude;
    private long locatetime;

    DatagramClient(DatagramSocket dgramSocket,
                   String stunServer,
                   int serverPort,
                   long uid,
                   double latitude,
                   double longitude,
                   long locatetime,
                   int timeout,
                   EstablishListener udpEstablishedListener) {
      super();
      this.dgramSocket = dgramSocket;
      this.stunServer = stunServer;
      this.serverPort = serverPort;
      this.uid = uid;
      this.latitude = latitude;
      this.longitude = longitude;
      this.timeout = timeout;
      this.locatetime = locatetime;
      this.agent = Agent.createBasicServer();
      this.udpEstablishedListener = udpEstablishedListener;
    }

    public void run() {

      //DatagramSocket dgramSocket = null;
      boolean isError = false;
      try {
        //dgramSocket = new DatagramSocket();
        //dgramSocket.setReuseAddress(true);
//        dgramSocket.connect(InetAddress.getByName(stunServer), serverPort);
        dgramSocket.setSoTimeout(timeout);

        System.out.println(String.format("Started datagram client on %s %d ", dgramSocket.getLocalAddress(), dgramSocket.getLocalPort()));

        Attribute attribute = LocationAttribute
                .createAttribute(uid, latitude, longitude, locatetime);
        AttributesCollection attributes = AttributesCollection.EMPTY_COLLECTION;

        byte[] attributeBytes = attributes.replyBuilder()
                .addAttribute(attribute)
                .build()
                .toByteArray();

        Message request = Message.builder()
                .setMessageClass(MESSAGE_CLASS_REQUEST)
                .setMessageMethod(MESSAGE_METHOD_PP)
                .generateTransactionID()
                .setAttributeBytes(attributeBytes)
                .build();

        byte[] requestBytes = request.getBytes();
        DatagramPacket replyPacket = new DatagramPacket(
                requestBytes, requestBytes.length);
        replyPacket.setSocketAddress(new InetSocketAddress(stunServer, serverPort));
        dgramSocket.send(replyPacket);

        byte[] packetBuffer = new byte[1024];

        while (true) {
          DatagramPacket dgramPacket = new DatagramPacket(packetBuffer, packetBuffer.length);

          dgramSocket.receive(dgramPacket);
          Message response = new Message(Preconditions.checkNotNull(packetBuffer));

          if (request.equalTransactionID(response)) {
            int packetLen = dgramPacket.getLength();
//            System.out.println(String.format("Received packet of size %d bytes", packetLen));
            byte[] msgBuffer = new byte[packetLen];
            System.arraycopy(packetBuffer, 0, msgBuffer, 0, packetLen);

            final InetSocketAddress remoteAddress =
                    new InetSocketAddress(dgramPacket.getAddress(), dgramPacket.getPort());
System.out.println(String.format("Received message from %s %d", dgramPacket.getAddress(), dgramPacket.getPort()));
            ResponseHandler rh =
                    createResponseHandler(dgramSocket);

            agent.onMessage(msgBuffer, remoteAddress, rh);
            break;
          }
        }

      } catch (UnknownHostException s) {
        System.out.println("Unknown stun server " + stunServer);
        s.printStackTrace();
        isError = true;
      } catch (PortUnreachableException s) {
        System.out.println("Unreachable stun server " + stunServer);
        s.printStackTrace();
        isError = true;
      } catch (SocketTimeoutException s) {
        System.out.println("Timeout stun server " + stunServer);
        s.printStackTrace();
        isError = true;
      } catch (SocketException s) {
        System.out.println("Unable to create new datagram socket");
        s.printStackTrace();
        isError = true;
      } catch (IOException e) {
        System.out.println("Unable to send to / receive from stun server " + stunServer);
        e.printStackTrace();
        isError = true;
      } finally  {
//        if (dgramSocket != null) {
//          if (dgramSocket.isConnected()) dgramSocket.disconnect();
//          if (!dgramSocket.isClosed()) dgramSocket.close();
//        }
      }

      if (isError && udpEstablishedListener != null) {
        if (this.timeout > 3000) {
          ErrorNotify task = new ErrorNotify(udpEstablishedListener);
          //timer.schedule(task, 10);
          ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
          executor.schedule(task, 10, TimeUnit.MILLISECONDS);
          executor.shutdown();

        } else {
          System.out.println("Failed. Re-try.... ");

          DatagramClient task = new DatagramClient(this.dgramSocket,
                  this.stunServer,
                  this.serverPort,
                  this.uid,
                  this.latitude,
                  this.longitude,
                  this.locatetime,
                  this.timeout * 2,
                  this.udpEstablishedListener);

          ScheduledExecutorService executor = Executors.newScheduledThreadPool(1);
          executor.schedule(task, 2000, TimeUnit.MILLISECONDS);
          executor.shutdown();
        }
      }

    }

    private int getPaddedLength(int length) {
      int remainder = length % 4;
      return remainder == 0 ? length : length + 4 - remainder;
    }

    private ResponseHandler createResponseHandler(
            final DatagramSocket dgramSocket) {
      return new ResponseHandler() {
        @Override
        public void onQuest(byte[] messageData, InetAddress destAddress, int destPort) {

        }

        @Override
        public void onResponse(byte[] messageData, InetAddress destAddress, int destPort) {

          int currentByte = 0;
          int type = Bytes.twoBytesToInt(messageData[currentByte++], messageData[currentByte++]);
          int length = Bytes.twoBytesToInt(messageData[currentByte++], messageData[currentByte++]);

          byte[] valueData;
          if (length > 0) {
            int paddedLength = getPaddedLength(length);
            valueData = new byte[paddedLength];
            // we can just copy to length, because the valueData array is already
            // initialized to 0 byte values
            System.arraycopy(messageData, currentByte, valueData, 0, length);
          } else {
            valueData = new byte[0];
          }

          AttributeFactory factory = new RFC5389AttributeFactory();
          MappedAddressAttribute mappedAttribute = (MappedAddressAttribute)
                  factory.createAttribute(type, length, valueData);

          try {
            InetAddress mappedAddr = InetAddress.getByAddress(mappedAttribute.getMappedAddress());

            FollowTask task = new FollowTask(mappedAddr.getHostAddress(),
                    mappedAttribute.getPort(),
                    dgramSocket.getLocalPort(),
                    udpEstablishedListener);
            //timer.schedule(task, 10);
            ExecutorService executor = Executors.newFixedThreadPool(1);
            executor.submit(task);
            executor.shutdown();

            System.out.println(String.format("Received Mapped Address: %s %d", mappedAddr.getHostAddress(), mappedAttribute.getPort()));
          } catch (UnknownHostException e) {
            e.printStackTrace();
          }

        }

        @Override
        public void onIndication(byte[] messageData, InetAddress destAddress, int destPort) {

        }
      };
    }
  }

}
