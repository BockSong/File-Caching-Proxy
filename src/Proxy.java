/* proxy.java */

import java.io.*;
import java.rmi.Naming;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.*;

class Proxy {

	public static final int EIO = -5;

	private static final String cache_split = "__cache/";
	// Note: need to add synchronized for func, in this way don't need to use lock (synchronized method)
	private static List<Integer> avail_fds = 
								Collections.synchronizedList(new ArrayList<Integer>());
	// compelte record of used fd and corresponding <file>
	private static ConcurrentHashMap<Integer, File> fd_f = new 
								ConcurrentHashMap<Integer, File>();
	// may try synchronizedmap if this one is not good enough
	private static ConcurrentHashMap<Integer, RandomAccessFile> fd_raf = new 
								ConcurrentHashMap<Integer, RandomAccessFile>();

    private static ConcurrentHashMap<String, Integer> oriPath_verID = new 
                                ConcurrentHashMap<String, Integer>();

	private static int cachesize;
	private static String cachedir;

	private static ServerIntf server;
	
	private static void init( String ca_dir, int ca_size ) {
		cachesize = ca_size;
		cachedir = ca_dir;

		// avail_fds: 0-1023
		for (int i = 0; i< 1024; i++)
			avail_fds.add(i); 
	}

	private static String get_localPath( String path ) {
		return cachedir + "/" + path;
	}

	private static String get_oriPath( String path ) {
		return path.substring(cachedir.length() + 1);
	}

	private static String copyPath2oriPath( String path ) {
		String oriPath = path.substring(0, path.lastIndexOf(cache_split));
		System.out.println("[copyPath2oriPath] copyPath: " + path);
		System.out.println("[copyPath2oriPath] oriPath: " + oriPath);
		return oriPath;
	}

	/*
	 * copy_file: copy the file from srcPath to desPath.
	 */
	private static void copy_file( String srcPath, String desPath ) {
		try {
			File srcFile = new File(srcPath);
			byte buffer[] = new byte[(int) srcFile.length()];
			BufferedInputStream reader = new
			BufferedInputStream(new FileInputStream(srcPath));
			reader.read(buffer, 0, buffer.length);
			reader.close();

			BufferedOutputStream writer = new
			BufferedOutputStream(new FileOutputStream(desPath));
			writer.write(buffer, 0, buffer.length);
			writer.flush();
			writer.close();
		} catch (Exception e) {
			System.out.println("Error in copy_file: " + e.getMessage());
			e.printStackTrace();
		}
	}

	/*
	 * make_copy: make a copy of file corresponed with fd and redirect fd to the new file.
	 */
	private static void make_copy( int fd ) {
		File oriFile = fd_f.get(fd), copyFile;
		String oriPath = oriFile.getPath();
		String copyPath, fileName; 

		fileName = oriFile.getName();
		File copyDir = new File(oriPath + cache_split);
		// if it doesn't exist, create this directory
		if ( !copyDir.exists() && !copyDir.mkdirs() ) {
			System.out.println("Error: unable to make new directory in cache!");
		};

		// find an available copyPath
		for (int i = 0; ; i++) {
			copyPath = oriPath + cache_split + fileName + "_" + i;
			copyFile = new File(copyPath);
			if (!copyFile.exists()) {
				System.out.println("[make_copy] stored at: " + copyPath);
				break;
			}
		}

		// copy the file
		copy_file(oriPath, copyPath);

		// redirect fd to that new copy
		fd_f.remove(fd);
		fd_f.put(fd, copyFile);
		
		// TODO: add it in cache counting
	}

	/*
	 * remove_copy: see if fd a write request. if yes, overwrite the original file with 
	 * 				the copied version, and remove the copied version. if not, do nothing.
	 */
	private static void remove_copy( int fd ) {
		try {
			File copyFile = fd_f.get(fd);
			String copyPath = copyFile.getPath();

			// if it's a read, just return
			// TODO: this is kind of hardcoding
			if (copyPath.indexOf(cache_split) == -1)
				return;

			// find the original file
			String oriPath = copyPath2oriPath(copyPath);

			// overwrite the original file with the copy
			copy_file(copyPath, oriPath);

			// remove that copy
			if (!copyFile.delete()) {
				System.out.println("Error: delete file failed from " + oriPath);
			}

			// redirect fd to the original file
			File oriFile = new File(oriPath);
			fd_f.remove(fd);
			fd_f.put(fd, oriFile);
			
		} catch (Exception e) {
            System.out.println("Error in remove: " + e.getMessage());
            e.printStackTrace();
		}

		// TODO: clear it in cache counting
	}

	private static class FileHandler implements FileHandling {

		/*
		 * open: open or create a file for reading or writing
		 * 
		 * return: If successful, open() returns a non-negative integer, termed a file descriptor.
		 * It returns -1 on failure, and sets errno to indicate the error.
		 */
		public synchronized int open( String path, OpenOption o ) {
			int fd, remote_verID;
			File f;
			String mode, localPath;
			RandomAccessFile raf;
			Boolean update;
			
			localPath = get_localPath(path);
			System.out.println("--[OPEN] called from localPath: " + localPath);

			if (avail_fds.size() == 0)
				return Errors.EMFILE;

			f = new File(localPath);
			
			try {
				remote_verID = server.getVersionID(path);
				if (f.exists() && f.isFile()) {
					System.out.println("local_verID: " + oriPath_verID.get(path) + " remote_verID: " + remote_verID);
				}
				else
					System.out.println("remote_verID: " + remote_verID);

				// check if we need to update cache
				if (remote_verID == -1) {
					update = false;  // if server doesn't have this file, don't update
				}
				else if (f.exists() && f.isFile() && (remote_verID <= oriPath_verID.get(path)) ) {
					update = false;  // if we have this file and it's the newest, don't update
				}
				else {
					update = true;  // otherwise update
				}

				if (update) {
					System.out.println("downloading of path: " + path);
					FileInfo fi = server.getFile(path);
					// In case somethig wrong; non-exist file shouldn't be transmitted
					if (fi.exist) {
						if (fi.isFile) {
							// if it's a file, write it to local cache
							byte[] fi_data = fi.filedata;
							BufferedOutputStream writer = new 
							BufferedOutputStream(new FileOutputStream(localPath));
							writer.write(fi_data, 0, fi_data.length);
							writer.flush();
							writer.close();

							// update the file-verID pair
							// Q: what if there multiple clients trying to add pairs?
							// It should be fine, just written multiple times and last win!
							oriPath_verID.put(path, remote_verID);
						}
						else {
							// if it's a directory and doesn't exist locally, make it
							if (!f.exists() && !f.mkdirs()) {
								System.out.println("Error: unable to make new directory in cache!");
							};
						}
					}
					else {
						System.out.println("[Error] this directory does not exist remotely.");
					}
				}
				else {
					System.out.println("Local file is already up-to-date. ");
				}
			} catch (Exception e) {
				System.out.println("Error in downloading: " + e.getMessage());
				e.printStackTrace();
			}

			// now start deal with local cache
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

			// Mark: move synchronized keyword to function
			fd = avail_fds.get(0);
			avail_fds.remove(0);

			fd_f.put(fd, f);

			// if it's a write, make a copy first
			if (mode == "rw") {
				make_copy(fd);
				f = fd_f.get(fd);
			}

			// Cannot actually open a directory using RandomAccessFile
			if (!f.isDirectory()) {
				try {
					raf = new RandomAccessFile(f.getPath(), mode);
					fd_raf.put(fd, raf);

					// add the pair if the file is just created
					if (!oriPath_verID.containsKey(path))
						oriPath_verID.put(path, 0);
				} catch (Exception e) {
					System.out.println("throw IOException");
					return EIO;
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
		public synchronized int close( int fd ) {
			File f;
			int local_verID, remote_verID;
			String oriPath;
			RandomAccessFile raf;
			System.out.println("--[CLOSE] called from " + fd);
			if (!fd_raf.containsKey(fd))
				return Errors.EBADF;

			try {
				// if it's a write, remove the copy
				remove_copy(fd);

				f = fd_f.get(fd);

				oriPath = get_oriPath(f.getPath());
				local_verID = oriPath_verID.get(oriPath);
				remote_verID = server.getVersionID(oriPath);
				
				// if f is a file and it's newer than server, then upload it to server
				if (!f.isDirectory() && (local_verID > remote_verID)) {
						raf = fd_raf.get(fd);
						raf.close();
						
						// use RPC call to upload a file from cache
						System.out.println("Local verID: " + local_verID + " (" + remote_verID + ")");
						System.out.println("uploading of oriPath: " + oriPath);

						byte buffer[] = new byte[(int) f.length()];
						BufferedInputStream reader = new 
						BufferedInputStream(new FileInputStream(f.getPath()));
						reader.read(buffer, 0, buffer.length);
						reader.close();

						FileInfo fi = new FileInfo(oriPath, buffer, local_verID);
						server.setFile(fi);

						fd_raf.remove(fd);
				}
				else {
					System.out.println("Local file didn't change. ");
				}
			} catch (Exception e) {
				System.out.println("Error in close: " + e.getMessage());
				e.printStackTrace();
			}

			fd_f.remove(fd);

			// Mark: move synchronized keyword to function
			avail_fds.add(fd);

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
		public synchronized long write( int fd, byte[] buf ) {
			File f;
			String oriPath;
			RandomAccessFile raf;
			System.out.println("--[WRITE] called from " + fd);
			if (!fd_f.containsKey(fd))
				return Errors.EBADF;

			f = fd_f.get(fd);
			if (f.isDirectory())
				return Errors.EISDIR;

			raf = fd_raf.get(fd);

			try {
				// just local execution
				raf.write(buf);
			} catch (Exception e) {
				System.out.println("throw IO exception");
				// since we can catch permission error here, 
				// r/w permissions of files are not explicitly stored
				if (e instanceof IOException)
					return Errors.EBADF;
				return EIO;
			}

			oriPath = get_oriPath( copyPath2oriPath(f.getPath()) );
			// update versionID
			// Mark: move synchronized keyword to function
			oriPath_verID.put(oriPath, oriPath_verID.get(oriPath) + 1);
				
			System.out.println("file " + oriPath + "'s verID update to " + oriPath_verID.get(oriPath));

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
		public synchronized long read( int fd, byte[] buf ) {
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
				// just local execution
				read_len = raf.read(buf);
			} catch (Exception e) {
				System.out.println("throw IO exception");
				return EIO;
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
		public synchronized long lseek( int fd, long pos, LseekOption o ) {
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
	
				// just local execution
				raf.seek(seek_loc);
			} catch (Exception e) {
				System.out.println("throw IO exception");
				return EIO;
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
		public synchronized int unlink( String path ) {
			int rv = -1;
			File f;
			System.out.println("--[UNLINK] called from " + path);
			
			try {
				// delete the file from server
				rv = server.unlink(path);
				// TODO: already opened file need to still could use them
				// in this case do we still remove rv's record in proxy? do we have to? if so, when?		
			} catch (Exception e) {
				System.out.println("Error in unlink: " + e.getMessage());
				e.printStackTrace();
			}
			System.out.println(" ");
			return rv;
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
		// check args number
        if(args.length != 4) {
            System.out.println("Usage: java Proxy serverip port cachedir cachesize");
            System.exit(0);
        }

		// These are args to connect to the server
		int port, ca_size;
		String serverip, ca_dir;
		serverip = args[0];
		ca_dir = args[2];
		try {
			port = Integer.parseInt(args[1]);
			ca_size = Integer.parseInt(args[3]);
		} catch (NumberFormatException e)
		{
			System.out.println("NumberFormatException in parsing args. \n");
			port = 15440;
			ca_size = 100000;
		}
		System.out.println("Server ip: " + serverip + "\nServer port: " + port);
		System.out.println("cachedir: " + ca_dir + "\ncachesize: " + ca_size);

		init(ca_dir, ca_size);
		System.out.println("Proxy initialized.");

		// connect to server
		try {
			server = (ServerIntf) Naming.lookup("//" + serverip + ":" + port + "/ServerIntf");
			System.out.println("Connection built.");
		} catch (Exception e) {
			System.out.println("NotBoundException in connection. \n");
		}

		(new RPCreceiver(new FileHandlingFactory())).run();
	}
}
