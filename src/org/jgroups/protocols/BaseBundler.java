package org.jgroups.protocols;

import org.jgroups.Address;
import org.jgroups.Message;
import org.jgroups.View;
import org.jgroups.annotations.GuardedBy;
import org.jgroups.annotations.ManagedAttribute;
import org.jgroups.annotations.Property;
import org.jgroups.conf.AttributeType;
import org.jgroups.logging.Log;
import org.jgroups.stack.MessageProcessingPolicy;
import org.jgroups.util.AverageMinMax;
import org.jgroups.util.ByteArrayDataOutputStream;
import org.jgroups.util.MessageBatch;
import org.jgroups.util.Util;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.jgroups.Message.TransientFlag.DONT_LOOPBACK;
import static org.jgroups.protocols.TP.MSG_OVERHEAD;
import static org.jgroups.util.MessageBatch.Mode.OOB;
import static org.jgroups.util.MessageBatch.Mode.REG;

/**
 * Implements storing of messages in a hashmap and sending of single messages and message batches. Most bundler
 * implementations will want to extend this class
 * @author Bela Ban
 * @since  4.0
 */
public abstract class BaseBundler implements Bundler {
    /** Keys are destinations, values are lists of Messages */
    protected final Map<Address,List<Message>>  msgs=new HashMap<>(24);
    protected TP                                transport;
    protected MessageProcessingPolicy           msg_processing_policy;
    protected final ReentrantLock               lock=new ReentrantLock();
    protected @GuardedBy("lock") long           count;    // current number of bytes accumulated
    protected ByteArrayDataOutputStream         output;
    protected Log                               log;

    /**
     * Maximum number of bytes for messages to be queued until they are sent.
     * This value needs to be smaller than the largest datagram packet size in case of UDP
     */
    @Property(name="max_size", type=AttributeType.BYTES,
      description="Maximum number of bytes for messages to be queued until they are sent")
    protected int                               max_size=64000;

    @Property(description="The max number of elements in a bundler if the bundler supports size limitations",
      type=AttributeType.SCALAR)
    protected int                               capacity=16384;

    @Property(description="Whether loopback messages (dest == src or dest == null) are processed")
    protected boolean                           process_loopbacks=true;

    @ManagedAttribute(description="Time (us) to send the bundled messages")
    protected final AverageMinMax               avg_send_time=new AverageMinMax().unit(TimeUnit.NANOSECONDS);


    public int     getCapacity()               {return capacity;}
    public Bundler setCapacity(int c)          {this.capacity=c; return this;}
    public int     getMaxSize()                {return max_size;}
    public Bundler setMaxSize(int s)           {max_size=s; return this;}
    public boolean processLoopbacks()          {return process_loopbacks;}
    public Bundler processLoopbacks(boolean b) {process_loopbacks=b; return this;}

    public void init(TP transport) {
        this.transport=transport;
        msg_processing_policy=transport.msgProcessingPolicy();
        log=transport.getLog();
        output=new ByteArrayDataOutputStream(max_size + MSG_OVERHEAD);
    }

    public void resetStats() {
        avg_send_time.clear();
    }

    public void start() {}
    public void stop()  {}
    public void send(Message msg) throws Exception {}

    public void viewChange(View view) {
        // code removed (https://issues.redhat.com/browse/JGRP-2324)
    }

    /** Returns the total number of messages in the hashmap */
    @ManagedAttribute(description="The number of unsent messages in the bundler")
    public int size() {
        lock.lock();
        try {
            return msgs.values().stream().map(List::size).reduce(0, Integer::sum);
        }
        finally {
            lock.unlock();
        }
    }

    @ManagedAttribute(description="Size of the queue (if available")
    public int getQueueSize() {
        return -1;
    }

    /**
     * Sends all messages in the map. Messages for the same destination are bundled into a message list.
     * The map will be cleared when done.
     */
    @GuardedBy("lock") protected void sendBundledMessages() {
        boolean stats_enabled=transport.statsEnabled();
        long start=stats_enabled? System.nanoTime() : 0;
        for(Map.Entry<Address,List<Message>> entry: msgs.entrySet()) {
            List<Message> list=entry.getValue();
            if(list.isEmpty())
                continue;
            Address dst=entry.getKey();
            boolean loopback=(dst == null) || Objects.equals(transport.getAddress(), dst);
            output.position(0);

            // System.out.printf("-- sending %d msgs to %s\n", list.size(), dst);

            if(list.size() == 1) {
                Message msg=list.get(0);
                sendSingleMessage(msg);
                if(process_loopbacks && loopback && !msg.isFlagSet(DONT_LOOPBACK) && transport.loopbackSeparateThread())
                    transport.loopback(msg, msg.isFlagSet(Message.Flag.OOB));
            }
            else {
                sendMessageList(dst, list.get(0).getSrc(), list);
                if(process_loopbacks && loopback && transport.loopbackSeparateThread())
                    loopback(dst, transport.getAddress(), list);
            }
            list.clear();
        }
        count=0;
        if(stats_enabled) {
            long time=System.nanoTime() - start;
            avg_send_time.add(time);
        }
    }

    protected void loopback(Address dest, Address sender, List<Message> list) {



        // TODO: reuse message batches, similar to ReliableMulticast.removeAndDeliver()

        // TODO: implement loopback in other Bundlers (not extending this one), too


        MessageBatch oob=new MessageBatch(dest, sender, transport.getClusterNameAscii(), dest == null, OOB, list.size());
        MessageBatch reg=new MessageBatch(dest, sender, transport.getClusterNameAscii(), dest == null, REG, list.size());
        for(Message msg: list) {
            if(msg.isFlagSet(DONT_LOOPBACK))
                continue;
            if(msg.isFlagSet(Message.Flag.OOB))
                oob.add(msg);
            else
                reg.add(msg);
        }
        if(!reg.isEmpty())
            msg_processing_policy.loopback(reg, false);
        if(!oob.isEmpty())
            msg_processing_policy.loopback(oob, true);
    }

    protected void sendSingleMessage(final Message msg) {
        Address dest=msg.getDest();
        try {
            Util.writeMessage(msg, output, dest == null);
            transport.doSend(output.buffer(), 0, output.position(), dest);
            transport.getMessageStats().incrNumSingleMsgsSent();
        }
        catch(Throwable e) {
            log.trace(Util.getMessage("SendFailure"),
                      transport.getAddress(), (dest == null? "cluster" : dest), msg.size(), e.toString(), msg.printHeaders());
        }
    }



    protected void sendMessageList(final Address dest, final Address src, final List<Message> list) {
        try {
            Util.writeMessageList(dest, src, transport.cluster_name.chars(), list, output, dest == null);
            transport.doSend(output.buffer(), 0, output.position(), dest);
            transport.getMessageStats().incrNumBatchesSent();
        }
        catch(Throwable e) {
            log.trace(Util.getMessage("FailureSendingMsgBundle"), transport.getAddress(), e);
        }
    }

    @GuardedBy("lock") protected void addMessage(Message msg, int size) {
        Address dest=msg.getDest();
        List<Message> tmp=msgs.computeIfAbsent(dest, k -> new ArrayList<>(16));
        tmp.add(msg);
        count+=size;
    }
}