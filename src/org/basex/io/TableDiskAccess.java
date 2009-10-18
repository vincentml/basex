package org.basex.io;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;

import org.basex.core.Main;
import org.basex.core.Prop;
import org.basex.util.Array;

/**
 * This class stores the table on disk and reads it block-wise.
 *
 * @author Workgroup DBIS, University of Konstanz 2005-09, ISC License
 * @author Christian Gruen
 * @author Tim Petrowsky
 */
public final class TableDiskAccess extends TableAccess {
  /** Max entries per block. */
  private static final int ENTRIES = IO.BLOCKSIZE >>> IO.NODEPOWER;
  /** Entries per new block. */
  private static final int NEWENTRIES = (int) (IO.BLOCKFILL * ENTRIES);

  /** Buffer manager. */
  private final Buffers bm = new Buffers();
  /** Current buffer. */
  private Buffer bf;

  /** File storing all blocks. */
  public final RandomAccessFile file;
  /** Filename prefix. */
  private final String pref;
  /** Name of the database. */
  private final String db;
  /** Database properties. */
  private final Prop prop;

  /** Index array storing the FirstPre values. this one is sorted ascending. */
  private int[] firstPres;
  /** Index array storing the BlockNumbers. */
  private int[] blocks;

  /** Number of the first entry in the current block. */
  private int firstPre = -1;
  /** FirstPre of the next block. */
  private int nextPre = -1;

  /** Number of entries in the index (used blocks). */
  private int indexSize;
  /** Number of blocks in the data file (including unused). */
  private int nrBlocks;
  /** Index number of the current block, referencing indexBlockNumber array. */
  private int index = -1;
  /** Number of entries in the storage. */
  private int count;
  /** Dirty index flag. */
  private boolean dirty;

  /**
   * Constructor.
   * @param nm name of the database
   * @param f prefix for all files (no ending)
   * @param pr database properties
   * @throws IOException I/O exception
   */
  public TableDiskAccess(final String nm, final String f, final Prop pr)
      throws IOException {
    db = nm;
    pref = f;
    prop = pr;

    // READ INFO FILE AND INDEX
    final DataInput in = new DataInput(pr.dbfile(nm, f + 'i'));
    nrBlocks = in.readNum();
    indexSize = in.readNum();
    count = in.readNum();
    firstPres = in.readNums();
    blocks = in.readNums();
    in.close();

    // INITIALIZE FILE
    file = new RandomAccessFile(pr.dbfile(nm, f), "rw");
    readBlock(0, 0, indexSize > 1 ? firstPres[1] : count);
  }

  /**
   * Searches for the block containing the entry for that pre. then it
   * reads the block and returns it's offset inside the block.
   * @param pre pre of the entry to search for
   * @return offset of the entry in currentBlock
   */
  private synchronized int cursor(final int pre) {
    int fp = firstPre;
    int np = nextPre;

    if(pre < fp || pre >= np) {
      final int last = indexSize - 1;
      int l = 0;
      int h = last;
      int m = index;
      while(l <= h) {
        if(pre < fp) h = m - 1;
        else if(pre >= np) l = m + 1;
        else break;
        m = h + l >>> 1;
        fp = firstPres[m];
        np = m == last ? fp + ENTRIES : firstPres[m + 1];
      }
      if(l > h) Main.notexpected("Invalid Data Access [pre:" + pre +
          ", indexSize:" + indexSize + ", access:" + l + " > " + h + "]");

      readBlock(m, fp, np);
    }
    return pre - firstPre << IO.NODEPOWER;
  }

  /**
   * Fetches the requested block into blockNumber and set firstPre and
   * blockSize.
   * @param ind index number of the block to fetch
   * @param first first entry in that block
   * @param next first entry in the next block
   */
  private synchronized void readBlock(final int ind, final int first,
      final int next) {

    index = ind;
    firstPre = first;
    nextPre = next;

    final int b = blocks[ind];
    final boolean ch = bm.cursor(b);
    bf = bm.curr();
    if(ch) {
      try {
        if(bf.dirty) writeBlock(bf);
        bf.pos = b;
        file.seek(bf.pos * IO.BLOCKSIZE);
        file.read(bf.buf);
      } catch(final IOException ex) {
        ex.printStackTrace();
      }
    }
  }

  /**
   * Checks whether the current block needs to be written and write it.
   * @param buf buffer to write
   * @throws IOException I/O exception
   */
  private synchronized void writeBlock(final Buffer buf) throws IOException {
    file.seek(buf.pos * IO.BLOCKSIZE);
    file.write(buf.buf);
    buf.dirty = false;
  }

  /**
   * Fetches next block.
   */
  private synchronized void nextBlock() {
    readBlock(index + 1, nextPre, index + 2 >= indexSize ? count :
      firstPres[index + 2]);
  }

  @Override
  public synchronized void flush() throws IOException {
    for(final Buffer b : bm.all()) if(b.dirty) writeBlock(b);

    if(dirty) {
      final DataOutput out = new DataOutput(prop.dbfile(db, pref + 'i'));
      out.writeNum(nrBlocks);
      out.writeNum(indexSize);
      out.writeNum(count);
      out.writeNums(firstPres);
      out.writeNums(blocks);
      out.close();
      dirty = false;
    }
  }

  @Override
  public synchronized void close() throws IOException {
    flush();
    file.close();
  }

  @Override
  public synchronized int read1(final int pre, final int off) {
    final int o = off + cursor(pre);
    final byte[] b = bf.buf;
    return b[o] & 0xFF;
  }

  @Override
  public synchronized int read2(final int pre, final int off) {
    final int o = off + cursor(pre);
    final byte[] b = bf.buf;
    return ((b[o] & 0xFF) << 8) + (b[o + 1] & 0xFF);
  }

  @Override
  public synchronized int read4(final int pre, final int off) {
    final int o = off + cursor(pre);
    final byte[] b = bf.buf;
    return ((b[o] & 0xFF) << 24) + ((b[o + 1] & 0xFF) << 16) +
      ((b[o + 2] & 0xFF) << 8) + (b[o + 3] & 0xFF);
  }

  @Override
  public synchronized long read5(final int pre, final int off) {
    final int o = off + cursor(pre);
    final byte[] b = bf.buf;
    return ((long) (b[o] & 0xFF) << 32) + ((long) (b[o + 1] & 0xFF) << 24) +
      ((b[o + 2] & 0xFF) << 16) + ((b[o + 3] & 0xFF) << 8) + (b[o + 4] & 0xFF);
  }

  @Override
  public synchronized void write1(final int pre, final int off, final int v) {
    final int o = off + cursor(pre);
    final byte[] b = bf.buf;
    b[o] = (byte) v;
    bf.dirty = true;
  }

  @Override
  public synchronized void write2(final int pre, final int off, final int v) {
    final int o = off + cursor(pre);
    final byte[] b = bf.buf;
    b[o] = (byte) (v >>> 8);
    b[o + 1] = (byte) v;
    bf.dirty = true;
  }

  @Override
  public synchronized void write4(final int pre, final int off, final int v) {
    final int o = off + cursor(pre);
    final byte[] b = bf.buf;
    b[o]     = (byte) (v >>> 24);
    b[o + 1] = (byte) (v >>> 16);
    b[o + 2] = (byte) (v >>> 8);
    b[o + 3] = (byte) v;
    bf.dirty = true;
  }

  @Override
  public synchronized void write5(final int pre, final int off, final long v) {
    final int o = off + cursor(pre);
    final byte[] b = bf.buf;
    b[o]     = (byte) (v >>> 32);
    b[o + 1] = (byte) (v >>> 24);
    b[o + 2] = (byte) (v >>> 16);
    b[o + 3] = (byte) (v >>> 8);
    b[o + 4] = (byte) v;
    bf.dirty = true;
  }

  /* Note to delete method: Freed blocks are currently ignored. */

  @Override
  public synchronized void delete(final int first, final int nr) {
    // mark index as dirty and get first block
    dirty = true;
    cursor(first);

    // some useful variables to make code more readable
    int from = first - firstPre;
    final int last = first + nr;

    // check if all entries are in current block => handle and return
    if(last - 1 < nextPre) {
      copy(bf.buf, from + nr, bf.buf, from, nextPre - last);

      updatePre(nr);

      // if whole block was deleted, remove it from the index
      if(nextPre == firstPre) {
        Array.move(firstPres, index + 1, -1, indexSize - index - 1);
        Array.move(blocks, index + 1, -1, indexSize - index - 1);
        readBlock(index, firstPre, index + 2 > --indexSize ? count :
          firstPres[index + 1]);
      }
      return;
    }

    // handle blocks whose entries are to be deleted entirely

    // first count them
    int unused = 0;
    while(nextPre < last) {
      if(from == 0) unused++;
      nextBlock();
      from = 0;
    }

    // now remove them from the index
    if(unused > 0) {
      Array.move(firstPres, index, -unused, indexSize - index);
      Array.move(blocks, index, -unused, indexSize - index);
      indexSize -= unused;
      index -= unused;
    }

    // delete entries at beginning of current (last) block
    copy(bf.buf, last - firstPre, bf.buf, 0, nextPre - last);

    // update index entry for this block
    firstPres[index] = first;
    firstPre = first;
    updatePre(nr);
  }

  /**
   * Updates the firstPre index entries.
   * @param nr number of entries to move
   */
  private synchronized void updatePre(final int nr) {
    // update index entries for all following blocks and reduce counter
    for(int i = index + 1; i < indexSize; i++) firstPres[i] -= nr;
    count -= nr;
    nextPre = index + 1 >= indexSize ? count : firstPres[index + 1];
  }

  @Override
  public synchronized void insert(final int pre, final byte[] entries) {
    dirty = true;
    final int nr = entries.length >>> IO.NODEPOWER;
    count += nr;
    cursor(pre - 1);

    final int ins = pre - firstPre;

    // all entries fit in current block
    if(nr < ENTRIES - nextPre + firstPre) {
      // shift following entries forward and insert next entries
      copy(bf.buf, ins, bf.buf, ins + nr, nextPre - pre + 1);
      copy(entries, 0, bf.buf, ins, nr);

      // update index entries
      for(int i = index + 1; i < indexSize; i++) firstPres[i] += nr;
      nextPre += nr;
      return;
    }

    // we need to reorganize entries

    // save entries to be added into new block after inserted blocks
    final int move = nextPre - pre;
    final byte[] rest = new byte[move << IO.NODEPOWER];

    copy(bf.buf, ins, rest, 0, move);

    // make room in index for new blocks
    int newBlocks = (int) Math.ceil((double) nr / NEWENTRIES) + 1;
    // in case we insert at block boundary
    if(pre == nextPre) newBlocks--;

    // resize the index
    final int s = nrBlocks + newBlocks;
    firstPres = Arrays.copyOf(firstPres, s);
    blocks = Arrays.copyOf(blocks, s);

    Array.move(firstPres, index + 1, newBlocks, indexSize - index - 1);
    Array.move(blocks, index + 1, newBlocks, indexSize - index - 1);

    // add blocks for new entries
    int remain = nr;
    int pos = 0;
    while(remain > 0) {
      newBlock();
      copy(entries, pos, bf.buf, 0, Math.min(remain, NEWENTRIES));

      firstPres[++index] = nr - remain + pre;
      blocks[index] = (int) bf.pos;
      indexSize++;
      remain -= NEWENTRIES;
      pos += NEWENTRIES;
    }

    // add remaining part of split block
    if(rest.length > 0) {
      newBlock();
      copy(rest, 0, bf.buf, 0, move);

      firstPres[++index] = pre + nr;
      blocks[index] = (int) bf.pos;
      indexSize++;
    }

    // update index entries
    for(int i = index + 1; i < indexSize; i++) firstPres[i] += nr;

    // update cached variables
    firstPre = pre;
    if(rest.length > 0) firstPre += nr;
    nextPre = index + 1 >= indexSize ? count : firstPres[index + 1];
  }

  /**
   * Creates a new, empty block.
   */
  private synchronized void newBlock() {
    try {
      if(bf.dirty) writeBlock(bf);
    } catch(final IOException ex) {
      ex.printStackTrace();
    }
    bf.pos = nrBlocks++;
    bf.dirty = true;
  }

  /**
   * Convenience method for copying blocks.
   * @param s source array
   * @param sp source position
   * @param d destination array
   * @param dp destination position
   * @param l source length
   */
  private synchronized void copy(final byte[] s, final int sp, final byte[] d,
      final int dp, final int l) {
    System.arraycopy(s, sp << IO.NODEPOWER, d, dp << IO.NODEPOWER,
        l << IO.NODEPOWER);
    bf.dirty = true;
  }

  /**
   * Returns the entryCount; needed for JUnit tests.
   * @return number of entries in storage.
   */
  public synchronized int size() {
    return count;
  }

  /**
   * Returns the number of used blocks; needed for JUnit tests.
   * @return number of used blocks.
   */
  public synchronized int blocks() {
    return indexSize;
  }
}
