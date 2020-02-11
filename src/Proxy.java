/* Sample skeleton for proxy */

import java.io.*;
import java.util.List;
import java.util.Map;

class Proxy {

	// may try synchronizedmap if this one is not good enough
	private static Map<Integer, RandomAccessFile> fd_raf;
	//private static List<Integer> avail_fds; // will be useless if use Java's nature fd
	
	private static void init() {
		fd_raf = new ConcurrentHashMap<String, RandomAccessFile>();
		//avail_fds = new ArrayList<>(1024); // 0-1023
	}

	private static class FileHandler implements FileHandling {

		/*
		 * open: open or create a file for reading or writing
		 * 
		 * return: If successful, open() returns a non-negative integer, termed a file descriptor.
		 * It returns -1 on failure, and sets errno to indicate the error.
		 */ 
		public int open( String path, OpenOption o ) {
			System.out.println("OPEN called from " + path);
			String mode;
			// TODO: handle errno ?
			int errno = 0;
			switch (o) {
				case OpenOption.READ:
					mode = "r";
					break;
				// TODO: what is defference between write and create_new??
				case OpenOption.WRITE:
					mode = "rw";
					break;
				case OpenOption.CREATE:
					mode = "rw";
					break;
				case OpenOption.CREATE_NEW:
					mode = "rw";
					break;
			}
			RandomAccessFile raf = new RandomAccessFile(path, mode);
			int fd = raf.getFD();
			fd_raf.put(fd, raf);
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
			System.out.println("close called from " + fd);
			int errno = 0;
			try {
				RandomAccessFile raf = fd_raf.get(fd);
				raf.close();
				fd_raf.remove(fd);
				//avail_fds.add(fd);
			} catch (Exception e) {
				//TODO: handle exception
				errno = -1;
			}
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
			System.out.println("write called from " + fd);
			int length = 0, errno = 0;
			try {
				RandomAccessFile raf = fd_raf.get(fd);
				raf.write(buf);
			} catch (Exception e) {
				//TODO: handle exception
			}
			return length;
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
			System.out.println("read called from " + fd);
			int length = 0, errno = 0;
			try {
				RandomAccessFile raf = fd_raf.get(fd);
				raf.read(buf);
			} catch (Exception e) {
				//TODO: handle exception
			}
			return length;
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
			System.out.println("lseek called from " + fd);
			int length = 0, errno = 0;
			try {
				RandomAccessFile raf = fd_raf.get(fd);
				raf.read(buf);
			} catch (Exception e) {
				//TODO: handle exception
			}
			return length;
		}

		/* 
		 * unlink: removes the link named by path from its directory and decrements the link 
		 * count of the file which was referenced by the link.
		 * 
		 * return: Upon successful completion, a value of 0 is returned.  Otherwise, a value of
		 * -1 is returned and errno is set to indicate the error.
		 */
		public int unlink( String path ) {
			System.out.println("unlink called from " + path);
			int errno = 0;
			try {
				// delete
			} catch (Exception e) {
				//TODO: handle exception
				errno = -1;
				return -1;
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

