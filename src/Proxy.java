/* Sample skeleton for proxy */

import java.io.*;

class Proxy {
	
	private static class FileHandler implements FileHandling {

		public int open( String path, OpenOption o ) {
			System.out.println("OPEN\n");
			File f = new File(path);
			return Errors.ENOSYS;
		}

		public int close( int fd ) {
            System.out.println("close\n");
			return Errors.ENOSYS;
		}

		public long write( int fd, byte[] buf ) {
            System.out.println("write\n");
			return Errors.ENOSYS;
		}

		public long read( int fd, byte[] buf ) {
            System.out.println("read\n");
			return Errors.ENOSYS;
		}

		public long lseek( int fd, long pos, LseekOption o ) {
            System.out.println("lseek\n");
			return Errors.ENOSYS;
		}

		public int unlink( String path ) {
            System.out.println("unlink\n");
			return Errors.ENOSYS;
		}

		public void clientdone() {
            System.out.println("clientdone\n");
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
		(new RPCreceiver(new FileHandlingFactory())).run();
	}
}

