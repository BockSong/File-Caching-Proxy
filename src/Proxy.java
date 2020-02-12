/* Sample skeleton for proxy */

import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.*;

class Proxy {

	// seems that don't work, still need to use synchronized()
	private static List<Integer> avail_fds = Collections.synchronizedList(new ArrayList<Integer>());
	// here is a compelte record of fd (and the corresponding file)
	private static ConcurrentHashMap<Integer, File> fd_f = new ConcurrentHashMap<Integer, File>();
	// may try synchronizedmap if this one is not good enough
	private static ConcurrentHashMap<Integer, RandomAccessFile> fd_raf = new ConcurrentHashMap<Integer, RandomAccessFile>();
	
	private static void init() {
		// avail_fds: 0-1023
		for (int i = 0; i< 1024; i++)
			avail_fds.add(i); 
	}

	private static class FileHandler implements FileHandling {

		/*
		 * open: open or create a file for reading or writing
		 * 
		 * return: If successful, open() returns a non-negative integer, termed a file descriptor.
		 * It returns -1 on failure, and sets errno to indicate the error.
		 */
		public int open( String path, OpenOption o ) {
			int fd;
			File f;
			String mode;
			RandomAccessFile raf;
			System.out.println("--[OPEN] called from " + path);

			if (avail_fds.size() == 0)
				return Errors.EMFILE;

			f = new File(path);

			switch (o) {
				case READ:
					// must exist
					if (!f.exists())
						return Errors.ENOENT;
					mode = "r";
					break;
				case WRITE:
					// must exist
					if (!f.exists())
						return Errors.ENOENT;
					// must be file rather than directory
					if (f.isDirectory())
						return Errors.EISDIR;
					mode = "rw";
					break;
				// both ok
				case CREATE:
					// if exist, must be file rather than directory
					if (f.exists() && f.isDirectory())
						return Errors.EISDIR;
					mode = "rw";
					break;
				case CREATE_NEW:
					// must not exist
					if (f.exists())
						return Errors.EEXIST;
					mode = "rw";
					break;
				default:
					return Errors.EINVAL;
			}

			synchronized (avail_fds) {
				fd = avail_fds.get(0);
				avail_fds.remove(0);
			}
			fd_f.put(fd, f);

			// Cannot actually open a directory using RandomAccessFile
			if (!f.isDirectory()) {
				try {
					raf = new RandomAccessFile(path, mode);
					fd_raf.put(fd, raf);
				} catch (Exception e) {
					System.out.println("throw IOException");
					return -5;  // EIO
				}
			}

			System.out.println("OPEN call done from " + fd + " mode: " + mode);
			System.out.println(" ");
			return fd;
		}

		/*
		 * close: delete a descriptor
		 * 
		 * return: Upon successful completion, a value of 0 is returned.  Otherwise, a value of
     	 * -1 is returned and the global integer variable errno is set to indicate the
		 * error.
		 */
		public int close( int fd ) {
			File f;
			RandomAccessFile raf;
			System.out.println("--[CLOSE] called from " + fd);
			if (!fd_raf.containsKey(fd))
				return Errors.EBADF;

			f = fd_f.get(fd);
			
			if (!f.isDirectory()) {
				try {
					raf = fd_raf.get(fd);
					raf.close();
					fd_raf.remove(fd);
				} catch (Exception e) {
					System.out.println("throw IO exception");
					return -5;  // Errors.EIO
				}
			}

			fd_f.remove(fd);
			synchronized (avail_fds) {
				avail_fds.add(fd);
			}

			System.out.println(" ");
			return 0;
		}

		/*
		 * write: write() attempts to write nbyte of data to the object referenced by the
		 * descriptor fildes from the buffer pointed to by buf.
		 * 
		 * return: Upon successful completion the number of bytes which were written is returned.
     	 * Otherwise, a -1 is returned and the global variable errno is set to indicate
		 * the error.
		 */  
		public long write( int fd, byte[] buf ) {
			File f;
			RandomAccessFile raf;
			System.out.println("--[WRITE] called from " + fd);
			if (!fd_f.containsKey(fd))
				return Errors.EBADF;

			f = fd_f.get(fd);
			if (f.isDirectory())
				return Errors.EISDIR;

			raf = fd_raf.get(fd);

			try {
				raf.write(buf);
			} catch (Exception e) {
				System.out.println("throw IO exception");
				// since we can catch permission error here, read/write permissions of files are not explicitly stored
				if (e instanceof IOException)
					return Errors.EBADF;
				return -5;  // Errors.EIO
			}

			System.out.println("Write " + buf.length + " byte: " + buf);
			System.out.println(" ");
			return buf.length;
		}

		/*
		 * read: attempts to read nbyte bytes of data from the object referenced by the
		 * descriptor fildes into the buffer pointed to by buf.
		 * 
		 * return: If successful, the number of bytes actually read is returned.  Upon reading
     	 * end-of-file, zero is returned.  Otherwise, a -1 is returned and the global
		 * variable errno is set to indicate the error. 
		 */ 
		public long read( int fd, byte[] buf ) {
			File f;
			int read_len;
			RandomAccessFile raf;
			System.out.println("--[READ] called from " + fd);
			if (!fd_f.containsKey(fd))
				return Errors.EBADF;

			f = fd_f.get(fd);
			if (f.isDirectory())
				return Errors.EISDIR;

			raf = fd_raf.get(fd);

			try {
				read_len = raf.read(buf);
			} catch (Exception e) {
				System.out.println("throw IO exception");
				return -5;  // Errors.EIO
			}
			if (read_len == -1)
				read_len = 0;

			System.out.println("Read " + read_len + " byte: " + buf);
			System.out.println(" ");
			return read_len;
		}

		/*
		 * lseek: repositions the offset of the file descriptor fildes to the argument offset, 
		 * according to the directive whence.
		 * 
		 * return: Upon successful completion, lseek() returns the resulting offset location as
     	 * measured in bytes from the beginning of the file.  Otherwise, a value of -1 is
		 * returned and errno is set to indicate the error.
		 */
		public long lseek( int fd, long pos, LseekOption o ) {
			File f;
			long seek_loc = pos;
			RandomAccessFile raf;
			System.out.println("--[LSEEK] called from " + fd);
			if (!fd_f.containsKey(fd))
				return Errors.EBADF;

			f = fd_f.get(fd);
			if (f.isDirectory())
				return Errors.EISDIR;

			raf = fd_raf.get(fd);

			try {
				switch (o) {
					case FROM_START:
						break;
					case FROM_END:
						seek_loc += raf.length();
						break;
					case FROM_CURRENT:
						seek_loc += raf.getFilePointer();
						break;
					default:
						return Errors.EINVAL;
				}
	
				if (seek_loc < 0)
					return Errors.EINVAL;
	
				raf.seek(seek_loc);
			} catch (Exception e) {
				System.out.println("throw IO exception");
				return -5;  // Errors.EIO
			}

			System.out.println("pos: " + pos);
			System.out.println(" ");
			return seek_loc;
		}

		/* 
		 * unlink: removes the link named by path from its directory and decrements the link 
		 * count of the file which was referenced by the link.
		 * 
		 * return: Upon successful completion, a value of 0 is returned.  Otherwise, a value of
		 * -1 is returned and errno is set to indicate the error.
		 */
		public int unlink( String path ) {
			File f;
			System.out.println("--[UNLINK] called from " + path);
			
			f = new File(path);
			if (!f.exists())
				return Errors.ENOENT;
			if (f.isDirectory())
				return Errors.EISDIR;

			try {
				f.delete();
			} catch (Exception e) {
				System.out.println("throw IO exception");
				return -5;  // Errors.EIO
			}

			System.out.println(" ");
			return 0;
		}

		public void clientdone() {
			System.out.println("------client done\n");
			// clean up any state here
			return;
		}

	}
	
	private static class FileHandlingFactory implements FileHandlingMaking {
		public FileHandling newclient() {
			return new FileHandler();
		}
	}

	public static void main(String[] args) throws IOException {
		System.out.println("Hello World");
		init();
		(new RPCreceiver(new FileHandlingFactory())).run();
	}
}

