package com.mocyx.basic_client.bio;

import android.net.VpnService;
import android.util.Log;

import com.mocyx.basic_client.config.Config;
import com.mocyx.basic_client.protocol.tcpip.IpUtil;
import com.mocyx.basic_client.protocol.tcpip.Packet;
import com.mocyx.basic_client.protocol.tcpip.Packet.TCPHeader;
import com.mocyx.basic_client.protocol.tcpip.TCBStatus;
import com.mocyx.basic_client.util.ByteBufferPool;
import com.mocyx.basic_client.util.ObjAttrUtil;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.BlockingQueue;

public class NioSingleThreadTcpHandler implements Runnable {

    private static final String TAG = NioSingleThreadTcpHandler.class.getSimpleName();

    BlockingQueue<Packet> queue;//用于读包
    BlockingQueue<ByteBuffer> networkToDeviceQueue;//用于写数据
    VpnService vpnService;//用于保护地址

    private ObjAttrUtil objAttrUtil = new ObjAttrUtil();
    private Selector selector;

    private Map<String, TcpPipe> pipes = new HashMap<>();


    public NioSingleThreadTcpHandler(BlockingQueue<Packet> queue,//用于读包
                                     BlockingQueue<ByteBuffer> networkToDeviceQueue,//用于写数据
                                     VpnService vpnService//用于保护地址
    ) {
        this.queue = queue;
        this.vpnService = vpnService;
        this.networkToDeviceQueue = networkToDeviceQueue;
    }

    static class TcpPipe {
        public long mySequenceNum = 0;
        public long theirSequenceNum = 0;
        public long myAcknowledgementNum = 0;
        public long theirAcknowledgementNum = 0;
        static Integer tunnelIds = 0;
        public final int tunnelId = tunnelIds++;
        public String tunnelKey;
        public InetSocketAddress sourceAddress;
        public InetSocketAddress destinationAddress;
        public SocketChannel remote;
        public TCBStatus tcbStatus = TCBStatus.SYN_SENT;
        private ByteBuffer remoteOutBuffer = ByteBuffer.allocate(8 * 1024);
        //
        public boolean upActive = true;
        public boolean downActive = true;

        public int packId = 1;
        public long timestamp=0L;

        int synCount = 0;

    }


    private TcpPipe initPipe(Packet packet) throws Exception {
        TcpPipe pipe = new TcpPipe();
        pipe.sourceAddress = new InetSocketAddress(packet.ip4Header.sourceAddress, packet.tcpHeader.sourcePort);
        pipe.destinationAddress = new InetSocketAddress(packet.ip4Header.destinationAddress, packet.tcpHeader.destinationPort);
        pipe.remote = SocketChannel.open();
        objAttrUtil.setAttr(pipe.remote, "type", "remote");
        objAttrUtil.setAttr(pipe.remote, "pipe", pipe);
        pipe.remote.configureBlocking(false);
        SelectionKey key = pipe.remote.register(selector, SelectionKey.OP_CONNECT);
        objAttrUtil.setAttr(pipe.remote, "key", key);
        //very important, protect
        vpnService.protect(pipe.remote.socket());
        boolean b1 = pipe.remote.connect(pipe.destinationAddress);
        pipe.timestamp=System.currentTimeMillis();
        Log.i(TAG, String.format("initPipe %s %s", pipe.destinationAddress, b1));
        return pipe;
    }


    private static int HEADER_SIZE = Packet.IP4_HEADER_SIZE + Packet.TCP_HEADER_SIZE;

    private void sendTcpPack(TcpPipe pipe, byte flag, byte[] data) {
        int dataLen = 0;
        if (data != null) {
            dataLen = data.length;
        }
        Packet packet = IpUtil.buildTcpPacket(pipe.destinationAddress, pipe.sourceAddress, flag,
                pipe.myAcknowledgementNum, pipe.mySequenceNum, pipe.packId);
        pipe.packId += 1;
        ByteBuffer byteBuffer = ByteBuffer.allocate(HEADER_SIZE + dataLen);
        //
        byteBuffer.position(HEADER_SIZE);
        if (data != null) {
            if (byteBuffer.remaining() < data.length) {
                System.currentTimeMillis();
            }
            byteBuffer.put(data);
        }
        //
        packet.updateTCPBuffer(byteBuffer, flag, pipe.mySequenceNum, pipe.myAcknowledgementNum, dataLen);
        byteBuffer.position(HEADER_SIZE + dataLen);
        //
        networkToDeviceQueue.offer(byteBuffer);
        //
        if ((flag & (byte) TCPHeader.SYN) != 0) {
            pipe.mySequenceNum += 1;
        }
        if ((flag & (byte) TCPHeader.FIN) != 0) {
            pipe.mySequenceNum += 1;
        }
        if ((flag & (byte) TCPHeader.ACK) != 0) {
            pipe.mySequenceNum += dataLen;
        }
    }

    private void handleSyn(Packet packet, TcpPipe pipe) {
        if (pipe.tcbStatus == TCBStatus.SYN_SENT) {
            pipe.tcbStatus = TCBStatus.SYN_RECEIVED;
            Log.i(TAG, String.format("handleSyn %s %s", pipe.destinationAddress, pipe.tcbStatus));
        }
        Log.i(TAG, String.format("handleSyn  %d %d", pipe.tunnelId, packet.packId));
        TCPHeader tcpHeader = packet.tcpHeader;
        if (pipe.synCount == 0) {
            pipe.mySequenceNum = 1;
            pipe.theirSequenceNum = tcpHeader.sequenceNumber;
            pipe.myAcknowledgementNum = tcpHeader.sequenceNumber + 1;
            pipe.theirAcknowledgementNum = tcpHeader.acknowledgementNumber;
            sendTcpPack(pipe, (byte) (TCPHeader.SYN | TCPHeader.ACK), null);
        } else {
            pipe.myAcknowledgementNum = tcpHeader.sequenceNumber + 1;
        }
        pipe.synCount += 1;
    }

    private void handleRst(Packet packet, TcpPipe pipe) {
        Log.i(TAG, String.format("handleRst %d", pipe.tunnelId));
        pipe.upActive = false;
        pipe.downActive = false;
        cleanPipe(pipe);
        pipe.tcbStatus = TCBStatus.CLOSE_WAIT;
    }

    private void handleAck(Packet packet, TcpPipe pipe) throws Exception {
        if (pipe.tcbStatus == TCBStatus.SYN_RECEIVED) {
            pipe.tcbStatus = TCBStatus.ESTABLISHED;

            Log.i(TAG, String.format("handleAck %s %s", pipe.destinationAddress, pipe.tcbStatus));
        }

        if (Config.logAck) {
            Log.d(TAG, String.format("handleAck %d ", packet.packId));
        }

        TCPHeader tcpHeader = packet.tcpHeader;
        int payloadSize = packet.backingBuffer.remaining();

        if (payloadSize == 0) {
            return;
        }

        long newAck = tcpHeader.sequenceNumber + payloadSize;
        if (newAck <= pipe.myAcknowledgementNum) {
            if (Config.logAck) {
                Log.d(TAG, String.format("handleAck duplicate ack", pipe.myAcknowledgementNum, newAck));
            }
            return;
        }

        pipe.myAcknowledgementNum = tcpHeader.sequenceNumber;
        pipe.theirAcknowledgementNum = tcpHeader.acknowledgementNumber;

        pipe.myAcknowledgementNum += payloadSize;
        //TODO
        pipe.remoteOutBuffer.put(packet.backingBuffer);
        pipe.remoteOutBuffer.flip();
        tryFlushWrite(pipe, pipe.remote);
        sendTcpPack(pipe, (byte) TCPHeader.ACK, null);
        System.currentTimeMillis();
    }

    private SelectionKey getKey(SocketChannel channel) {
        return (SelectionKey) objAttrUtil.getAttr(channel, "key");
    }

    private boolean tryFlushWrite(TcpPipe pipe, SocketChannel channel) throws Exception {

        ByteBuffer buffer = pipe.remoteOutBuffer;
        if (pipe.remote.socket().isOutputShutdown() && buffer.remaining() != 0) {
            sendTcpPack(pipe, (byte) (TCPHeader.FIN | Packet.TCPHeader.ACK), null);
            buffer.compact();
            return false;
        }
        if (!channel.isConnected()) {
            Log.i(TAG, "not yet connected");
            SelectionKey key = (SelectionKey) objAttrUtil.getAttr(channel, "key");
            int ops = key.interestOps() | SelectionKey.OP_WRITE;
            key.interestOps(ops);
            System.currentTimeMillis();
            buffer.compact();
            return false;
        }
        while (buffer.hasRemaining()) {
            int n = 0;
            n = channel.write(buffer);
            if (n > 4000) {
                System.currentTimeMillis();
            }
            Log.i(TAG, String.format("tryFlushWrite write %s", n));
            if (n <= 0) {
                Log.i(TAG, "write fail");
                //
                SelectionKey key = (SelectionKey) objAttrUtil.getAttr(channel, "key");
                int ops = key.interestOps() | SelectionKey.OP_WRITE;
                key.interestOps(ops);
                System.currentTimeMillis();
                buffer.compact();
                return false;
            }
        }
        buffer.clear();
        if (!pipe.upActive) {
            pipe.remote.shutdownOutput();
        }
        return true;
    }


    private void closeUpStream(TcpPipe pipe) throws Exception {
        Log.i(TAG, String.format("closeUpStream %d", pipe.tunnelId));
        try {
            if (pipe.remote != null && pipe.remote.isOpen()) {
                if (pipe.remote.isConnected()) {
                    pipe.remote.shutdownOutput();
                }

            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        Log.i(TAG, String.format("closeUpStream %d", pipe.tunnelId));
        pipe.upActive = false;

        if (isClosedTunnel(pipe)) {
            cleanPipe(pipe);
        }
    }

    private void handleFin(Packet packet, TcpPipe pipe) throws Exception {
        Log.i(TAG, String.format("handleFin %d", pipe.tunnelId));
        pipe.myAcknowledgementNum = packet.tcpHeader.sequenceNumber + 1;
        pipe.theirAcknowledgementNum = packet.tcpHeader.acknowledgementNumber;
        //TODO
        sendTcpPack(pipe, (byte) (TCPHeader.ACK), null);
        closeUpStream(pipe);
        pipe.tcbStatus = TCBStatus.CLOSE_WAIT;

        Log.i(TAG, String.format("handleFin %s %s", pipe.destinationAddress, pipe.tcbStatus));
    }

    private void handlePacket(TcpPipe pipe, Packet packet) throws Exception {
        boolean end = false;
        TCPHeader tcpHeader = packet.tcpHeader;
        if (tcpHeader.isSYN()) {
            handleSyn(packet, pipe);
            end = true;
        }
        if (!end && tcpHeader.isRST()) {
            handleRst(packet, pipe);
            return;
        }
        if (!end && tcpHeader.isFIN()) {
            handleFin(packet, pipe);
            end = true;
        }
        if (!end && tcpHeader.isACK()) {
            handleAck(packet, pipe);
        }

    }

    private void handleReadFromVpn() throws Exception {
        while (true) {
            Packet currentPacket = queue.poll();
            if (currentPacket == null) {
                return;
            }
            InetAddress destinationAddress = currentPacket.ip4Header.destinationAddress;
            TCPHeader tcpHeader = currentPacket.tcpHeader;
            //Log.d(TAG, String.format("get pack %d tcp " + tcpHeader.printSimple() + " ", currentPacket.packId));
            int destinationPort = tcpHeader.destinationPort;
            int sourcePort = tcpHeader.sourcePort;
            String ipAndPort = destinationAddress.getHostAddress() + ":" +
                    destinationPort + ":" + sourcePort;


            if (!pipes.containsKey(ipAndPort)) {
                TcpPipe tcpTunnel = initPipe(currentPacket);
                tcpTunnel.tunnelKey = ipAndPort;
                pipes.put(ipAndPort, tcpTunnel);
            }
            TcpPipe pipe = pipes.get(ipAndPort);
            handlePacket(pipe, currentPacket);
            System.currentTimeMillis();
        }
    }

    private void doAccept(ServerSocketChannel serverChannel) throws Exception {
        throw new RuntimeException("");
    }

    private void doRead(SocketChannel channel) throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(4 * 1024);
        String quitType = "";

        TcpPipe pipe = (TcpPipe) objAttrUtil.getAttr(channel, "pipe");

        while (true) {
            buffer.clear();
            int n = BioUtil.read(channel, buffer);
            Log.i(TAG, String.format("read %s", n));
            if (n == -1) {
                quitType = "fin";
                break;
            } else if (n == 0) {
                break;
            } else {
                if (pipe.tcbStatus != TCBStatus.CLOSE_WAIT) {
                    buffer.flip();
                    byte[] data = new byte[buffer.remaining()];
                    buffer.get(data);
                    sendTcpPack(pipe, (byte) (TCPHeader.ACK), data);
                }
            }
        }
        if (quitType.equals("fin")) {
            closeDownStream(pipe);
        }
    }

    private void cleanPipe(TcpPipe pipe) {
        try {
            if (pipe.remote != null && pipe.remote.isOpen()) {
                pipe.remote.close();
            }
            pipes.remove(pipe.tunnelKey);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void closeRst(TcpPipe pipe) throws Exception {
        Log.i(TAG, String.format("closeRst %d", pipe.tunnelId));
        cleanPipe(pipe);
        sendTcpPack(pipe, (byte) TCPHeader.RST, null);
        pipe.upActive = false;
        pipe.downActive = false;
    }

    private void closeDownStream(TcpPipe pipe) throws Exception {
        Log.i(TAG, String.format("closeDownStream %d", pipe.tunnelId));
        if (pipe.remote != null && pipe.remote.isConnected()) {
            pipe.remote.shutdownInput();
            int ops = getKey(pipe.remote).interestOps() & (~SelectionKey.OP_READ);
            getKey(pipe.remote).interestOps(ops);
        }

        sendTcpPack(pipe, (byte) (TCPHeader.FIN | Packet.TCPHeader.ACK), null);
        pipe.downActive = false;
        if (isClosedTunnel(pipe)) {
            cleanPipe(pipe);
        }
    }

    public boolean isClosedTunnel(TcpPipe tunnel) {
        return !tunnel.upActive && !tunnel.downActive;
    }

    private void doConnect(SocketChannel socketChannel) throws Exception {
        Log.i(TAG, String.format("tick %s", tick));
        //
        String type = (String) objAttrUtil.getAttr(socketChannel, "type");
        TcpPipe pipe = (TcpPipe) objAttrUtil.getAttr(socketChannel, "pipe");
        SelectionKey key = (SelectionKey) objAttrUtil.getAttr(socketChannel, "key");
        if (type.equals("remote")) {
            boolean b1 = socketChannel.finishConnect();
            Log.i(TAG, String.format("connect %s %s %s", pipe.destinationAddress, b1,System.currentTimeMillis()-pipe.timestamp));
            pipe.timestamp=System.currentTimeMillis();
            pipe.remoteOutBuffer.flip();
            key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        }

    }

    private void doWrite(SocketChannel socketChannel) throws Exception {
        Log.i(TAG, String.format("tick %s", tick));
        TcpPipe pipe = (TcpPipe) objAttrUtil.getAttr(socketChannel, "pipe");
        boolean flushed = tryFlushWrite(pipe, socketChannel);
        if (flushed) {
            SelectionKey key1 = (SelectionKey) objAttrUtil.getAttr(socketChannel, "key");
            key1.interestOps(SelectionKey.OP_READ);
        }
    }


    private void handleSockets() throws Exception {

        while (selector.selectNow() > 0) {
            for (Iterator it = selector.selectedKeys().iterator(); it.hasNext(); ) {
                SelectionKey key = (SelectionKey) it.next();
                it.remove();
                TcpPipe pipe = (TcpPipe) objAttrUtil.getAttr(key.channel(), "pipe");
                if (key.isValid()) {
                    try {
                        if (key.isAcceptable()) {
                            doAccept((ServerSocketChannel) key.channel());
                        } else if (key.isReadable()) {
                            doRead((SocketChannel) key.channel());
                        } else if (key.isConnectable()) {
                            doConnect((SocketChannel) key.channel());
                            System.currentTimeMillis();
                        } else if (key.isWritable()) {
                            doWrite((SocketChannel) key.channel());
                            System.currentTimeMillis();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, e.getMessage(), e);
                        if (pipe != null) {
                            closeRst(pipe);
                        }
                    }
                }
            }
        }
    }

    private long tick = 0;

    @Override
    public void run() {
        try {
            selector = Selector.open();
            while (true) {
                handleReadFromVpn();
                handleSockets();
                tick += 1;
                Thread.sleep(1);
            }
        } catch (Exception e) {
            Log.e(e.getMessage(), "", e);
        }


    }
}


