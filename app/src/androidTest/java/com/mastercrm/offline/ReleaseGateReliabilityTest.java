package com.mastercrm.offline;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * DESTRUCTIVE integration tests for a DISPOSABLE CI emulator only.
 * setup() deletes the preview database. NEVER run against a real user's phone.
 * All records and JPEG-like samples here are synthetic.
 */
@RunWith(AndroidJUnit4.class)
public final class ReleaseGateReliabilityTest {
    private Context context;
    private CrmStore store;

    @Before public void setup() {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        context.deleteDatabase(CrmStore.DATABASE_NAME);
        store=new CrmStore(context);
    }

    @After public void tearDown() {
        store.close();
        context.deleteDatabase(CrmStore.DATABASE_NAME);
    }

    private long createClient(int index) {
        CrmStore.Client client=new CrmStore.Client();
        client.name="Synthetic test client "+index;
        return store.saveClient(client);
    }

    private long createJob(long clientId,int index) {
        CrmStore.Job job=new CrmStore.Job();
        job.clientId=clientId;
        job.title="Synthetic job "+index;
        job.price=180000L+index*100L;
        return store.saveJob(job);
    }

    private static byte[] fakeJpeg(Random random,int bytes) {
        byte[] photo=new byte[bytes];
        random.nextBytes(photo);
        photo[0]=(byte)0xff;photo[1]=(byte)0xd8;
        photo[bytes-2]=(byte)0xff;photo[bytes-1]=(byte)0xd9;
        return photo;
    }

    private byte[] export(char[] password) throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(OutputStream encrypted=StreamBackupCrypto.encrypt(output,password)) {
            StreamCrmArchive.write(store,encrypted);
        }
        return output.toByteArray();
    }

    private void restore(byte[] encrypted,char[] password) throws IOException {
        try(InputStream clear=StreamBackupCrypto.decrypt(
                new ByteArrayInputStream(encrypted),password)) {
            StreamCrmArchive.restore(store,clear);
        }
    }

    private void assertUnchanged(long jobId,int clients,long payment,long expense) {
        assertEquals("Client records must survive failed import",clients,
                store.summary().totalClients);
        assertNotNull("Existing order must survive failed import",store.job(jobId));
        assertEquals("Payment must survive failed import",payment,store.job(jobId).paid);
        assertEquals("Expense must survive failed import",expense,store.job(jobId).cost);
        assertEquals("Photo must survive failed import",1,store.photos(jobId).size());
    }

    private long seedData() {
        long client=createClient(7);
        long job=createJob(client,7);
        store.addPayment(job,25000,"synthetic advance");
        store.addExpense(job,6000,"Synthetic materials","");
        store.addPhoto(job,"before",fakeJpeg(new Random(77),8192));
        return job;
    }

    @Test public void fullDiskDuringEncryptedExportFailsWithoutChangingCrm() throws Exception {
        long job=seedData();
        char[] pass="Test backup password for disk full 1.9".toCharArray();
        // Simulates an external file provider that stops accepting bytes after
        // writing part of the protected output. Never touches the live database.
        OutputStream limited=new OutputStream() {
            private int remaining=1200;
            @Override public void write(int value) throws IOException {
                if(remaining--<=0)throw new IOException("Synthetic disk full");
            }
            @Override public void write(byte[] bytes,int offset,int length) throws IOException {
                for(int i=0;i<length;i++)write(bytes[offset+i]);
            }
        };
        try {
            try(OutputStream encrypted=StreamBackupCrypto.encrypt(limited,pass)) {
                StreamCrmArchive.write(store,encrypted);
            }
            fail("A limited output must fail instead of claiming a saved backup");
        } catch(IOException expected) {
            assertTrue(expected.getMessage()!=null);
        } finally {
            Arrays.fill(pass,'\0');
        }
        assertUnchanged(job,1,25000,6000);
    }

    @Test public void damagedAuthenticatedDataCannotOverwriteLiveCrm() throws Exception {
        long job=seedData();
        char[] pass="Test backup password for bitflip 1.9".toCharArray();
        try {
            byte[] encrypted=export(pass);
            assertTrue(encrypted.length>200);
            encrypted[encrypted.length/2]^=0x40;
            try {
                restore(encrypted,pass);
                fail("A bit-flipped AES-GCM archive must not restore");
            } catch(IOException expected) {
                // The entire transaction, including photo deletions, rolls back.
            }
            assertUnchanged(job,1,25000,6000);
        } finally {
            Arrays.fill(pass,'\0');
        }
    }

    @Test public void fileProviderReadFailureCannotPartiallyRestore() throws Exception {
        long job=seedData();
        char[] pass="Test backup password for read failure 1.9".toCharArray();
        try {
            byte[] archived=export(pass);
            assertTrue(archived.length>500);
            final int limit=archived.length/2;
            InputStream unreliable=new FilterInputStream(new ByteArrayInputStream(archived)) {
                private int read=0;
                @Override public int read() throws IOException {
                    if(read>=limit)throw new IOException("Synthetic provider read failure");
                    int next=in.read();
                    if(next>=0)read++;
                    return next;
                }
                @Override public int read(byte[] bytes,int offset,int length)
                        throws IOException {
                    if(read>=limit)throw new IOException("Synthetic provider read failure");
                    int n=in.read(bytes,offset,Math.min(length,limit-read));
                    if(n>0)read+=n;
                    return n;
                }
            };
            try(InputStream clear=StreamBackupCrypto.decrypt(unreliable,pass)) {
                StreamCrmArchive.restore(store,clear);
                fail("A file-provider read error must not restore");
            } catch(IOException expected) {
                // Import transaction is rolled back.
            }
            assertUnchanged(job,1,25000,6000);
        } finally {
            Arrays.fill(pass,'\0');
        }
    }

    @Test public void oneHundredTwentyRealJpegImagesRoundTripAndDecode()
            throws Exception {
        long client=createClient(2000);
        long[] photoIds=new long[120];
        int count=0;
        // Unlike JPEG-like byte arrays, these are genuine compressed Android
        // bitmaps in three sizes and portrait/landscape orientations.
        for(int order=0;order<12;order++) {
            long job=createJob(client,order);
            for(int photoIndex=0;photoIndex<10;photoIndex++) {
                int shape=(order+photoIndex)%3;
                int width=shape==0?720:(shape==1?320:480);
                int height=shape==0?480:(shape==1?640:320);
                android.graphics.Bitmap source=android.graphics.Bitmap.createBitmap(
                        width,height,android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.Canvas canvas=new android.graphics.Canvas(source);
                android.graphics.Paint brush=new android.graphics.Paint();
                brush.setColor(android.graphics.Color.rgb(
                        (order*19+photoIndex*7)%256,
                        (order*29+photoIndex*13)%256,
                        (order*53+photoIndex*3)%256));
                canvas.drawColor(brush.getColor());
                brush.setColor(android.graphics.Color.WHITE);
                canvas.drawRect(photoIndex+6,order+7,width/2,height/2,brush);
                java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
                assertTrue(source.compress(android.graphics.Bitmap.CompressFormat.JPEG,
                        86,bytes));
                source.recycle();
                byte[] jpeg=bytes.toByteArray();
                assertTrue("Real JPEG should be large enough",jpeg.length>1000);
                photoIds[count++]=store.addPhoto(job,photoIndex<5?"before":"after",jpeg);
            }
        }
        assertEquals(120,count);
        char[] password="Synthetic real JPEG release test password".toCharArray();
        try {
            byte[] encrypted=export(password);
            try(InputStream verified=StreamBackupCrypto.decrypt(
                    new ByteArrayInputStream(encrypted),password)) {
                StreamCrmArchive.VerificationResult result=
                        StreamCrmArchive.verify(store,verified);
                assertEquals(12,result.jobs);
                assertEquals(120,result.photos);
            }
            restore(encrypted,password);
            for(int i=0;i<photoIds.length;i++) {
                // A single 2 MB BLOB can be decoded without loading all photos.
                long orderId=(i/10)+1;
                CrmStore.Photo full=store.photo(photoIds[i],orderId);
                assertNotNull("JPEG must survive archive restore",full);
                android.graphics.Bitmap decoded=PhotoBitmapDecoder.decode(full.jpeg,320);
                assertNotNull("Restored JPEG must be decodable",decoded);
                assertTrue(decoded.getWidth()<=320&&decoded.getHeight()<=320);
                decoded.recycle();
            }
        } finally {
            Arrays.fill(password,'\\0');
        }
    }

    @Test public void largeSyntheticCrmRoundTripPreservesAllMoneyAndPhotos()
            throws Exception {
        long[] clients=new long[600];
        for(int i=0;i<clients.length;i++)clients[i]=createClient(i);
        Random random=new Random(1900);
        for(int i=0;i<1400;i++) {
            long job=createJob(clients[i%clients.length],i);
            if(i%4==0) {
                store.addPayment(job,15000,"Synthetic payment");
                store.addExpense(job,3000,"Synthetic expense","");
            }
            if(i<80) {
                for(int j=0;j<10;j++) {
                    store.addPhoto(job,j<5?"before":"after",
                            fakeJpeg(random,4096));
                }
            }
        }

        char[] pass="Test backup password for stress 1.9".toCharArray();
        try {
            byte[] encrypted=export(pass);
            // The verification-only operation is a full trial import that
            // MUST be rolled back. Add a record after snapshot as a sentinel.
            long outside=createJob(createClient(9001),9001);
            try(InputStream input=StreamBackupCrypto.decrypt(
                    new ByteArrayInputStream(encrypted),pass)) {
                StreamCrmArchive.VerificationResult result=
                        StreamCrmArchive.verify(store,input);
                assertEquals(600,result.clients);
                assertEquals(1400,result.jobs);
                assertEquals(350,result.payments);
                assertEquals(350,result.expenses);
                assertEquals(800,result.photos);
            }
            assertNotNull("Verification must preserve newer data",store.job(outside));
            restore(encrypted,pass);
            assertNull("Restore must replace post-backup synthetic changes",store.job(outside));
            assertEquals(600,store.summary().totalClients);
            assertEquals(1400,store.summary().activeJobs);
            assertEquals(100,store.jobs("","",13).size());

            long payments=0, expenses=0, photos=0;
            for(int page=0;page<14;page++) {
                for(CrmStore.Job job:store.jobs("","",page)) {
                    payments+=job.paid;
                    expenses+=job.cost;
                    photos+=store.photos(job.id).size();
                }
            }
            assertEquals(350L*15000L,payments);
            assertEquals(350L*3000L,expenses);
            assertEquals(800L,photos);
        } finally {
            Arrays.fill(pass,'\0');
        }
    }
}
