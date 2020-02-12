/* Sample skeleton for proxy */

import java.io.*;
import java.util.List;
import java.util.Map;

class Proxy {

	// this should be thread-safe, no need to use synchronized()
	private static List<Integer> avail_fds = Collections.synchronizedList(new ArrayList<Integer>());
	// may try synchronizedmap if this one is not good enough
	private static Map<Integer, RandomAccessFile> fd_raf = new ConcurrentHashMap<String, RandomAccessFile>();
	// this is to store read/write permission for every files
	private static Map<Integer, File> fd_f = new ConcurrentHashMap<String, File>();
	
	private static void init() {
		for (int i = 0; i< 1024; i++)
			avail_fds.add(i); // 0-1023
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
			System.out.println("OPEN called from " + path);

			if (avail_fds.size() == 0)
				return Errors.EMFILE;

			f = new File(path);

			switch (o) {
				case OpenOption.READ:
					// must exist
					if (!f.exists())
						return Errors.ENOENT;
					mode = "r";
					break;
				case OpenOption.WRITE:
					// must exist
					if (!f.exists())
						return Errors.ENOENT;
					// must be file rather than directory
					if (f.isDirectory())
						return Errors.EISDIR;
					mode = "rw";
					break;
				// both ok
				case OpenOption.CREATE:
					// if exist, must be file rather than directory
					if (f.exists() && f.isDirectory())
						return Errors.EISDIR;
					mode = "rw";
					break;
				case OpenOption.CREATE_NEW:
					// must not exist
					if (!f.exists())
						return Errors.EEXIST;
					mode = "rw";
					break;
				default:
					return Errors.EINVAL;
			}

			raf = new RandomAccessFile(path, mode);
			fd = avail_fds.get(0);
			avail_fds.remove(0);
			fd_raf.put(fd, raf);
			fd_f.put(fd, f);

			System.out.println("OPEN call done from " + fd);
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
			RandomAccessFile raf;
			System.out.println("close called from " + fd);
			if (!fd_raf.containsKey(fd))
				return Errors.EBADF;

			raf = fd_raf.get(fd);
			
			try {
				raf.close();
			} catch (Exception e) {
				return Errors.EIO;
			}

			fd_raf.remove(fd);
			fd_f.remove(fd);
			avail_fds.add(fd);
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
			System.out.println("write called from " + fd);
			if (!fd_raf.containsKey(fd))
				return Errors.EBADF;

			f = fd_f.get(fd);
			if (f.isDirectory())
				return Errors.EISDIR;

			raf = fd_raf.get(fd);

			try {
				raf.write(buf);
			} catch (Exception e) {
				if (e == IOException)
					return Errors.EIO;
				return Errors.EIO;
			}
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
			System.out.println("read called from " + fd);
			if (!fd_raf.containsKey(fd))
				return Errors.EBADF;

			f = fd_f.get(fd);
			if (f.isDirectory())
				return Errors.EISDIR;

			raf = fd_raf.get(fd);

			// TODO: handle error
			try {
				read_len = raf.read(buf);
			} catch (Exception e) {
				return Errors.EIO;
			}
			if (read_len == -1)
				read_len = 0;
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
			long seek_loc = pos;
			RandomAccessFile raf;
			System.out.println("lseek called from " + fd);
			if (!fd_raf.containsKey(fd))
				return Errors.EBADF;

			if (o != OpenOption.READ && f.isDirectory())
				return Errors.EISDIR;

			raf = fd_raf.get(fd);

			switch (o) {
				case LseekOption.FROM_START:
					break;
				case LseekOption.FROM_END:
					seek_loc += raf.length();
					break;
				case LseekOption.FROM_CURRENT:
					seek_loc += raf.getFilePointer();
					break;
				default:
					return Errors.EINVAL;
			}

			if (seek_loc < 0)
				return Errors.EINVAL;

			// TODO: handle error
			try {
				raf.seek(seek_loc);
			} catch (Exception e) {
				return Errors.EIO;
			}
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
			System.out.println("unlink called from " + path);
			
			f = new File(path);
			if (!f.exists())
				return Errors.ENOENT;
			if (f.isDirectory())
				return Errors.EISDIR;

			try {
				f.delete();
			} catch (Exception e) {
				return Errors.EIO;
			}
			return 0;
		}

		public void clientdone() {
			System.out.println("clientdone\n");
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

