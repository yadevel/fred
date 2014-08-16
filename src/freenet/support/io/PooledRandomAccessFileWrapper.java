package freenet.support.io;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.Serializable;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Random;

import freenet.client.async.ClientContext;
import freenet.support.Logger;
import freenet.support.math.MersenneTwister;

/** Random access files with a limited number of open files, using a pool. 
 * LOCKING OPTIMISATION: Contention on closables likely here. It's not clear how to avoid that, FIXME.
 * However, this is doing disk I/O (even if cached, system calls), so maybe it's not a big deal ... 
 * 
 * FIXME does this need a shutdown hook? I don't see why it would matter ... ??? */
public class PooledRandomAccessFileWrapper implements LockableRandomAccessThing, Serializable {
    
    private static final long serialVersionUID = 1L;
    private static int MAX_OPEN_FDS = 100;
    static int OPEN_FDS = 0;
    static final LinkedHashSet<PooledRandomAccessFileWrapper> closables = new LinkedHashSet<PooledRandomAccessFileWrapper>();
    
    public final File file;
    private final boolean readOnly;
    /** >0 means locked. We will wait until we get the lock if necessary, this is always accurate. 
     * LOCKING: Synchronized on closables (i.e. static, but not the class). */
    private int lockLevel;
    /** The actual RAF. Non-null only if open. LOCKING: Synchronized on (this).
     * LOCKING: Always take (this) last, i.e. after closables. */
    private transient RandomAccessFile raf;
    private final long length;
    private boolean closed;
    /** -1 = not persistent-temp. Otherwise the ID. We need the ID so we can move files if the 
     * prefix changes. */
    private final long persistentTempID;
    private boolean secureDelete;
    
    /** Create a RAF backed by a file.
     * @param file 
     * @param readOnly
     * @param forceLength
     * @param seedRandom
     * @param persistentTempID The tempfile ID, or -1.
     * @throws IOException
     */
    public PooledRandomAccessFileWrapper(File file, boolean readOnly, long forceLength, Random seedRandom, long persistentTempID) throws IOException {
        this.file = file;
        this.readOnly = readOnly;
        this.persistentTempID = persistentTempID;
        lockLevel = 0;
        // Check the parameters and get the length.
        // Also, unlock() adds to the closeables queue, which is essential.
        RAFLock lock = lockOpen();
        try {
            long currentLength = raf.length();
            if(forceLength >= 0 && forceLength != currentLength) {
                // Preallocate space. We want predictable disk usage, not minimal disk usage, especially for downloads.
                raf.seek(0);
                MersenneTwister mt = null;
                if(seedRandom != null)
                    mt = new MersenneTwister(seedRandom.nextLong());
                byte[] buf = new byte[4096];
                for(long l = 0; l < forceLength; l+=4096) {
                    if(mt != null)
                        mt.nextBytes(buf);
                    int maxWrite = (int)Math.min(4096, forceLength - l);
                    raf.write(buf, 0, maxWrite);
                }
                assert(raf.getFilePointer() == forceLength);
                assert(raf.length() == forceLength);
                raf.setLength(forceLength); 
                currentLength = forceLength;
            }
            this.length = currentLength;
            lock.unlock();
        } catch (IOException e) {
            synchronized(this) {
                raf.close();
                raf = null;
            }
            throw e;
        }
    }

    public PooledRandomAccessFileWrapper(File file, String mode, byte[] initialContents,
            int offset, int size, long persistentTempID) throws IOException {
        this.file = file;
        this.readOnly = false;
        this.length = size;
        this.persistentTempID = persistentTempID;
        lockLevel = 0;
        RAFLock lock = lockOpen();
        try {
            raf.write(initialContents, offset, size);
            lock.unlock();
        } catch (IOException e) {
            synchronized(this) {
                raf.close();
                raf = null;
            }
            throw e;
        }
    }
    
    protected PooledRandomAccessFileWrapper() {
        // For serialization.
        file = null;
        readOnly = false;
        length = 0;
        persistentTempID = -1;
    }

    @Override
    public long size() throws IOException {
        return length;
    }

    @Override
    public void pread(long fileOffset, byte[] buf, int bufOffset, int length) throws IOException {
        if(fileOffset < 0) throw new IllegalArgumentException();
        RAFLock lock = lockOpen();
        try {
            // FIXME Use NIO! This is absurd!
            synchronized(this) {
                raf.seek(fileOffset);
                raf.readFully(buf, bufOffset, length);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void pwrite(long fileOffset, byte[] buf, int bufOffset, int length) throws IOException {
        if(fileOffset < 0) throw new IllegalArgumentException();
        if(readOnly) throw new IOException("Read only");
        RAFLock lock = lockOpen();
        try {
            if(fileOffset + length > this.length)
                throw new IOException("Length limit exceeded");
            // FIXME Use NIO (which has proper pwrite, with concurrency)! This is absurd!
            synchronized(this) {
                raf.seek(fileOffset);
                raf.write(buf, bufOffset, length);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        synchronized(closables) {
            if(lockLevel != 0)
                throw new IllegalStateException("Must unlock first!");
            closed = true;
            // Essential to avoid memory leak!
            // Potentially slow but only happens on close(). Plus the size of closables is bounded anyway by the fd limit.
            closables.remove(this);
            closeRAF();
        }
    }

    @Override
    public RAFLock lockOpen() throws IOException {
        RAFLock lock = new RAFLock() {

            @Override
            protected void innerUnlock() {
                PooledRandomAccessFileWrapper.this.unlock();
            }
            
        };
        synchronized(closables) {
            while(true) {
                if(closed) throw new IOException("Already closed");
                if(raf != null) {
                    lockLevel++; // Already open, may or may not be already locked.
                    return lock;
                } else if(OPEN_FDS < MAX_OPEN_FDS) {
                    lockLevel++;
                    OPEN_FDS++;
                    try {
                        raf = new RandomAccessFile(file, readOnly ? "r" : "rw");
                    } catch (IOException e) {
                        // Don't call unlock(), don't want to add to closables.
                        lockLevel--;
                        OPEN_FDS--;
                        throw e;
                    }
                    return lock;
                } else {
                    PooledRandomAccessFileWrapper closable = pollFirstClosable();
                    if(closable != null) {
                        closable.closeRAF();
                        continue;
                    }
                    try {
                        closables.wait();
                    } catch (InterruptedException e) {
                        // Ignore
                    }
                }
            }
        }
    }
    
    private PooledRandomAccessFileWrapper pollFirstClosable() {
        synchronized(closables) {
            Iterator<PooledRandomAccessFileWrapper> it = closables.iterator();
            if (it.hasNext()) {
                PooledRandomAccessFileWrapper first = it.next();
                it.remove();
                return first;
            }
            return null;
        }
    }
    
    /** Should be synchronized on class already */
    private void closeRAF() {
        if(!closed && lockLevel != 0) throw new IllegalStateException();
        if(raf == null) return;
        try {
            raf.close();
        } catch (IOException e) {
            Logger.error(this, "Error closing "+this+" : "+e, e);
        }
        raf = null;
        OPEN_FDS--;
    }

    private void unlock() {
        synchronized(closables) {
            lockLevel--;
            if(lockLevel > 0) return;
            closables.add(this);
            closables.notify();
        }
    }
    
    public void setSecureDelete(boolean secureDelete) {
        this.secureDelete = secureDelete;
    }

    @Override
    public void free() {
        close();
        if(secureDelete) {
            try {
                FileUtil.secureDelete(file);
            } catch (IOException e) {
                Logger.error(this, "Unable to delete "+file+" : "+e, e);
                System.err.println("Unable to delete temporary file "+file);
            }
        } else {
            file.delete();
        }
    }
    
    /** Set the size of the fd pool */
    public static void setMaxFDs(int max) {
        synchronized(closables) {
            if(max <= 0) throw new IllegalArgumentException();
            MAX_OPEN_FDS = max;
        }
    }

    /** How many fd's are open right now? Mainly for tests but also for stats. */
    public static int getOpenFDs() {
        return OPEN_FDS;
    }
    
    static int getClosableFDs() {
        synchronized(closables) {
            return closables.size();
        }
    }
    
    boolean isOpen() {
        synchronized(closables) {
            return raf != null;
        }
    }
    
    boolean isLocked() {
        synchronized(closables) {
            return lockLevel != 0;
        }
    }

    @Override
    public void onResume(ClientContext context) throws ResumeFailedException {
        if(!file.exists()) throw new ResumeFailedException("File does not exist");
        if(length > file.length()) throw new ResumeFailedException("Bad length");
    }
    
    public String toString() {
        return super.toString()+":"+file;
    }
    
    static final int MAGIC = 0x297c550a;
    static final int VERSION = 1;
    
    @Override
    public void storeTo(DataOutputStream dos) throws IOException {
        dos.writeInt(MAGIC);
        dos.writeInt(VERSION);
        dos.writeUTF(file.toString());
        dos.writeBoolean(readOnly);
        dos.writeLong(length);
        dos.writeLong(persistentTempID);
        dos.writeBoolean(secureDelete);
    }

    /** Caller has already checked magic 
     * @throws StorageFormatException 
     * @throws IOException 
     * @throws ResumeFailedException */
    PooledRandomAccessFileWrapper(DataInputStream dis, ClientContext context) 
    throws StorageFormatException, IOException, ResumeFailedException {
        int version = dis.readInt();
        if(version != VERSION) throw new StorageFormatException("Bad version");
        File f = new File(dis.readUTF());
        readOnly = dis.readBoolean();
        length = dis.readLong();
        persistentTempID = dis.readLong();
        secureDelete = dis.readBoolean();
        if(length < 0) throw new StorageFormatException("Bad length");
        if(persistentTempID != -1) {
            FilenameGenerator fg = context.persistentFG;
            // File must exist!
            if(!f.exists()) {
                // Maybe moved after the last checkpoint?
                f = fg.getFilename(persistentTempID);
                if(f.exists()) {
                    context.persistentFileTracker.register(f);
                    file = f;
                    return;
                }
            }
            file = fg.maybeMove(f, persistentTempID);
            if(!f.exists())
                throw new ResumeFailedException("Persistent tempfile lost "+f);
        } else {
            file = f;
            if(!f.exists())
                throw new ResumeFailedException("Lost file "+f);
        }
    }

}
