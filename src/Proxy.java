/* proxy.java */

import java.io.*;
import java.rmi.Naming;
import java.util.List;
import java.util.Map;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.concurrent.*;

class Proxy {

	public static final int EIO = -5;
	public static final int EACCES = -13;
	public static final int MAX_LEN = 819200;

	private static final String cache_split = "__cache/";
	// About synchronization: having used synchronized keyword for func, so don't need to use 
	// lock (synchronized method) for synchronized data structure
	private static List<Integer> avail_fds = 
								Collections.synchronizedList(new ArrayList<Integer>());
	// this is a compelte record of used fd and corresponding file
	private static ConcurrentHashMap<Integer, File> fd_f = new 
								ConcurrentHashMap<Integer, File>();
	// this map doesn't contains directories
	private static ConcurrentHashMap<Integer, RandomAccessFile> fd_raf = new 
								ConcurrentHashMap<Integer, RandomAccessFile>();
	// this one is to record the permission mode of fd
	private static ConcurrentHashMap<Integer, String> fd_mode = new 
								ConcurrentHashMap<Integer, String>();
	// this one is to record the versionID of a file
    private static ConcurrentHashMap<String, Integer> oriPath_verID = new 
								ConcurrentHashMap<String, Integer>();

	// Cache Structure
	// only contains original version (without copies), since evicts only happen to ori file
	private static Map<String, File> LRU_cache = Collections.synchronizedMap(new 
								LinkedHashMap<String, File>(16, 0.75f, true));
	// maintain the status (if it's evictable) of every cache objects (original version)
	// Add 1 right after opening before making copy, and minus 1 in close.
	// One object is evictable with 0, and not for positive.
	private static ConcurrentHashMap<String, Integer> cache_user_count = new 
								ConcurrentHashMap<String, Integer>();
	// maintain the status (if it's removeable) of every read copies
	// One read copy is removeable with 0, and not for positive.
    private static ConcurrentHashMap<String, Integer> readerCount = new 
								ConcurrentHashMap<String, Integer>();
	// For every opened file, record Canonical Path - Relative Path
	private static ConcurrentHashMap<String, String> opened_path = new 
								ConcurrentHashMap<String, String>();
	
	private static String cachedir;
	private static int cachesize;
	private static int sizeCached;  // keep this lower than cachesize all the time
	private static Object cache_lock = new Object();  // lock for accessing cache

	private static ServerIntf server;
	
	private static void init( String ca_dir, int ca_size ) {
		cachesize = ca_size;
		cachedir = ca_dir;
		sizeCached = 0;

		// avail_fds: 0-1023
		for (int i = 0; i< 1024; i++)
			avail_fds.add(i); 
	}

	private static String ori2localPath( String path ) {
		return cachedir + "/" + path;
	}

	private static String local2oriPath( String path ) {
		return path.substring(cachedir.length() + 1);
	}

	private static String copyPath2localPath( String path ) {
		String localPath = path.substring(0, path.lastIndexOf(cache_split));
		//System.out.println("[copyPath2localPath] copyPath: " + path);
		//System.out.println("[copyPath2localPath] localPath: " + localPath);
		return localPath;
	}

	/*
	 * copy_file: copy the file from srcPath to desPath in cache. srcPath must exist. desPath
	 * 			  doesn't have to be empty, will overwrite automatically if needed.
	 */
	private synchronized static void copy_file( String srcPath, String desPath ) {
		try {
			File srcFile = new File(srcPath);
			long file_len = srcFile.length();
			BufferedInputStream reader = new 
					BufferedInputStream(new FileInputStream(srcPath));
			RandomAccessFile writer;

			// use cache lock to ensure safely when modifying sizeCached
			synchronized (cache_lock) {
				// first clear desPath if it already contains sth
				File desFile = new File(desPath);
				if (desFile.length() != 0) {
					sizeCached -= desFile.length();
					if (!desFile.delete()) {
						System.out.println("[copy_file] Error: delete file failed from "
																			+ desPath);
					}
				}
				// before writing, check that if there's enough space in cache
				while (sizeCached + file_len > cachesize) {
					System.out.println("Not enough. sizeCache: " + sizeCached + 
														"; file_len:" + file_len);
					if (cache_evict() != 0) {
						System.out.println("[copy_file] Error occured in eviction");
						System.exit(-1);
					}
				}
				writer = new RandomAccessFile(desPath, "rw");

				if (file_len < MAX_LEN) {
					byte buffer[] = new byte[(int) srcFile.length()];
					reader.read(buffer, 0, buffer.length);
					writer.write(buffer);
				}
				else {
					// do chunking and copy
					long sent_len = 0;
					long send;

					while (sent_len < file_len) {
						send = Math.min(MAX_LEN, file_len - sent_len);
						byte buffer[] = new byte[(int) send];
						reader.read(buffer, 0, buffer.length);
						writer.write(buffer);
						sent_len += send;
						System.out.println("[copy_file] copied " + send + " bytes to " + desPath);
					}
				}
				sizeCached += file_len;
			}
			reader.close();
			writer.close();
			System.out.println("[copy_file] copy successfully, " + file_len + " bytes in total.");

		} catch (Exception e) {
			System.out.println("Error in copy_file: " + e.getMessage());
			e.printStackTrace();
		}
	}

	/*
	 * make_copy: For write request, make a copy of file corresponed with fd and redirect
	 * 			  fd to the new file. Every writer has their own copy. For read request, 
	 * 			  first check if the copy already exist. If not, create it. All readers 
	 * 			  share one copy.
	 */
	private synchronized static void make_copy( int fd ) {
		try {
			File oriFile = fd_f.get(fd), copyFile;
			String oriPath = oriFile.getPath();
			String copyPath, fileName; 
			String mode = fd_mode.get(fd);
	
			fileName = oriFile.getName();
			File copyDir = new File(oriPath + cache_split);
			// if it doesn't exist, create this directory
			if ( !copyDir.exists() && !copyDir.mkdirs() ) {
				System.out.println("[make_copy] Error: unable to make new directory in cache!");
			};
	
			if (mode == "r") {
				// reader copy path is definite
				copyPath = oriPath + cache_split + fileName + "_reader";
				copyFile = new File(copyPath);
			}
			else {
				// find an available copyPath for a new copy
				for (int i = 0; ; i++) {
					copyPath = oriPath + cache_split + fileName + "_" + i;
					copyFile = new File(copyPath);
					if (!copyFile.exists()) {
						System.out.println("[make_copy] Path is set as: " + copyPath);
						break;
					}
				}
			}
			
			// if oriFile exist and copyFile doesn't exist, make the copy
			if (oriFile.exists() && !copyFile.exists()) {
				copy_file(oriPath, copyPath);
			}
	
			// redirect fd to that new copy
			fd_f.remove(fd);
			fd_f.put(fd, copyFile);
		} catch (Exception e) {
			System.out.println("[make_copy] Error: " + e.getMessage());
			e.printStackTrace();
		}
	}

	/*
	 * update_copy: This function called from close(). If fd is a write request, overwrite 
	 * 				the original file with the copied version, and remove the copied version.
	 * 				if reader, only remove copy when there's no other reader.
	 */
	private synchronized static void update_copy( int fd ) {
		try {
			File copyFile = fd_f.get(fd), localFile;
			String copyPath = copyFile.getPath();
			String localPath = copyPath2localPath(copyPath);
			String oriPath = local2oriPath(localPath);
			String mode = fd_mode.get(fd);

			if (mode == "r") {
				// for read, decrease the # of reader
				readerCount.put(oriPath, readerCount.get(oriPath) - 1);
			}
			else {
				// for writer, overwrite the original file with the copy
				copy_file(copyPath, localPath);
			}

			if (readerCount.getOrDefault(oriPath, 0) == 0) {
				// except it's a read and there are still [other] readers, do nothing
				// otherwise, remove the copy and clear counting
				synchronized (cache_lock) {
					if (copyFile.length() != 0) {
						sizeCached -= copyFile.length();
						if (!copyFile.delete()) {
							System.out.println("[update_copy] Error: delete file failed from " + 
																					   copyPath);
						}
					}
				}
				System.out.println("[update_copy] copy removed from " + copyPath);
			}

			// now redirect fd to the original file
			localFile = new File(localPath);
			fd_f.remove(fd);
			fd_f.put(fd, localFile);
			
		} catch (Exception e) {
            System.out.println("[update_copy] Error: " + e.getMessage());
            e.printStackTrace();
		}
	}

	/*
	 * cache_evict: evict an object in cache based LRU algorithm.
	 * return 0 on success. If cache is empty, return -1. If there's other error, return -2.
	 */
	private synchronized static int cache_evict() {
		System.out.println("[cache_evict] Cache usage: " + sizeCached + "/" + cachesize);
		// if cache is already empty, return an error
		if (LRU_cache.size() == 0)
			return -1;
		try {
			File f_evict;
			String path_evict = "";  // ensured to find the one to replace
			Iterator iter = LRU_cache.entrySet().iterator();
			Map.Entry entry;

			// find the file to be evicted
			while (iter.hasNext()) {
				entry = (Map.Entry) iter.next();
				path_evict = entry.getKey().toString();

				// check if the file is evictablt (is opened by anyone)
				if (cache_user_count.get(path_evict) == 0) {
					break;
				}
			}
			f_evict = new File(path_evict);

			synchronized (cache_lock) {
				System.out.println("[cache_evict] Eviction: path: " + path_evict + "; length: " + f_evict.length());
				sizeCached -= f_evict.length();

				// remove it in LRU_cache
				LRU_cache.remove(path_evict);

				// delete the cache file
				if (!f_evict.delete()) {
					System.out.println("[cache_evict] Error: delete file failed from " + path_evict);
				}
			}
			System.out.println("[cache_evict] Cache usage: " + sizeCached + "/" + cachesize);
			return 0;
		} catch (Exception e) {
            System.out.println("[cache_evict] Error: " + e.getMessage());
			e.printStackTrace();
			return -2;
		}
	}

	/*
	 * print_cache: print cache and reader counting.
	 * For debugging use.
	 */
	private static void print_cache() {
		System.out.println("Cache usage: " + sizeCached + "/" + cachesize);
		Iterator iter = LRU_cache.entrySet().iterator();

		while (iter.hasNext()) {
			Map.Entry entry = (Map.Entry) iter.next();
			File f = new File(entry.getKey().toString());
			int num = cache_user_count.get(entry.getKey().toString());
			System.out.println(entry.getKey() + " of " + f.length() + " with " 
								+ num + " copies, approa " + (num + 1) * f.length() + " in total");
		}

		System.out.println("(readerCount) ");
		iter = readerCount.entrySet().iterator();

		while (iter.hasNext()) {
			Map.Entry entry = (Map.Entry) iter.next();
			System.out.print(entry.getKey() + ":" + entry.getValue() + ", ");
		}
		System.out.println(" ");
	}

	private static class FileHandler implements FileHandling {

		/*
		 * open: open or create a file for reading or writing
		 * 
		 * return: If successful, open() returns a non-negative integer, termed a file descriptor.
		 * It returns -1 on failure, and sets errno to indicate the error.
		 */
		public synchronized int open( String path, OpenOption o ) {
			File f;
			Boolean update;
			int fd, remote_verID;
			RandomAccessFile raf;
			String mode, localPath;
			String oriPath = path;  // general relative path without root prefix
			
			// if it's a absolute path, return an error
			if (oriPath.substring(0, 1).equals("/"))
				return EACCES;

			if (avail_fds.size() == 0)
				return Errors.EMFILE;

			localPath = ori2localPath(oriPath);
			f = new File(localPath);
			System.out.println("--[OPEN] called from localPath: " + localPath);

			try {
				System.out.println("CanonicalPath: " + f.getCanonicalPath());
				// if it's already opened, use the same path format as the first one
				if ( opened_path.containsKey(f.getCanonicalPath()) ) {
					oriPath = opened_path.get(f.getCanonicalPath());
					System.out.println("Find existing path format: " + oriPath);
					localPath = ori2localPath(oriPath);
					f = new File(localPath);
				}
				else {
					// check if it's inside the root folder
					File root = new File(cachedir);
					if (f.getCanonicalPath().indexOf(root.getCanonicalPath()) == -1) {
						return EACCES;
					}
					else {
						// if it's fine, add it to the path record
						opened_path.put(f.getCanonicalPath(), oriPath);
					}
				}
				
				remote_verID = server.getVersionID(oriPath);
				if (f.exists() && f.isFile()) {
					System.out.println("found local_verID: " + oriPath_verID.get(oriPath) + 
														" remote_verID: " + remote_verID);
				}
				else
					System.out.println("get remote_verID: " + remote_verID);

				// check if we need to update cache
				if (remote_verID == -1) {
					update = false;  // if server doesn't have this file, don't update
				}
				else if (f.exists() && f.isFile() && (remote_verID <= oriPath_verID.get(oriPath)) ) {
					update = false;  // if we have this file and it's the newest, don't update
				}
				else {
					update = true;  // otherwise update
				}

				if (update) {
					FileInfo fi = server.getFile(oriPath);
					// if it's a file, write it to local cache
					if (fi.isFile) {
						File Dir = new File(localPath.substring(0, localPath.lastIndexOf("/")) );
						// If its directory doesn't exist, create it first
						if ( !Dir.exists() && !Dir.mkdirs() ) {
							System.out.println("[open] Error: unable to make new directory in cache!");
						};
						
						byte[] fi_data;
						RandomAccessFile writer;

						// use cache lock to ensure safely on use of sizeCached
						synchronized (cache_lock) {
							// first clear original file and cache counting if it is not empty
							if (f.exists() && f.isFile() && (f.length() != 0)) {
								sizeCached -= f.length();
								if (!f.delete()) {
									System.out.println("[copy_file] Error: delete file failed from "
																					   + localPath);
								}
							}
							long file_len = fi.length;
							writer = new RandomAccessFile(localPath, "rw");
							
							// check that if there's enough space in cache to write
							while (sizeCached + file_len > cachesize) {
								System.out.println("Not enough. sizeCache: " + sizeCached 
																+ "; file_len:" + file_len);
								if (cache_evict() != 0)
									System.out.println("[open] Error occured in eviction");
							}
	
							// if it's single file, receive it directly; otherwise call getChunk
							if (fi.isChunking) {
								long recv_len = file_len;

								// do chunking and send
								while (recv_len > 0) {
									fi = server.getChunk(oriPath, file_len - recv_len);
									fi_data = fi.filedata;

									writer.write(fi_data);
									recv_len -= fi_data.length;
								}
							}
							else {
								fi_data = fi.filedata;
								writer.write(fi_data);
							}
							sizeCached += file_len;
						}
						writer.close();
						System.out.println("download successfully to: " + oriPath);

						// update the file-verID pair
						// If multiple clients try to update same pair, the last will win
						oriPath_verID.put(oriPath, remote_verID);
					}
					// if it's a directory and doesn't exist locally, make it
					else {
						if (!f.exists() && !f.mkdirs()) {
							System.out.println("[open] Error: unable to make new directory in cache!");
						};
					}
				}
				else {
					System.out.println("[open] No need to download file. ");
				}
			} catch (Exception e) {
				System.out.println("[open] Error: " + e.getMessage());
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
			fd_mode.put(fd, mode);

			// Cannot actually open a directory using RandomAccessFile
			if (!f.isDirectory()) {
				// maintain cache user counting (for eviction)
				cache_user_count.put(localPath, cache_user_count.getOrDefault(localPath, 0) + 1);

				// make a new copy for reader or writer if needed
				make_copy(fd);
				f = fd_f.get(fd);

				try {
					raf = new RandomAccessFile(f.getPath(), mode);

					fd_raf.put(fd, raf);
					LRU_cache.put(localPath, f);

					// add the pair if the file is just created
					if (!oriPath_verID.containsKey(oriPath))
						oriPath_verID.put(oriPath, 0);

					// if it's read, # of readers add 1
					if (mode == "r") {
						readerCount.put(oriPath, readerCount.getOrDefault(oriPath, 0) + 1);
					}

				} catch (Exception e) {
					System.out.println("throw IOException");
					return EIO;
				}
			}

			System.out.println("OPEN call done from " + fd + " mode: " + mode);
			print_cache();
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
			String oriPath, localPath;
			RandomAccessFile raf;
			System.out.println("--[CLOSE] called from " + fd);
			if (!fd_raf.containsKey(fd))
				return Errors.EBADF;

			try {
				// remove the copy if necessary
				update_copy(fd);

				f = fd_f.get(fd);
				localPath = f.getPath();

				oriPath = local2oriPath(localPath);
				local_verID = oriPath_verID.get(oriPath);
				remote_verID = server.getVersionID(oriPath);
				
				// set f as the most recent one in LRU_cache (automatically done by LinkedHashmap)
				LRU_cache.get(localPath);
				System.out.println("File usage recorded: " + localPath);

				// if f is a file and it's newer than server, then upload it to server
				if (!f.isDirectory() && (local_verID > remote_verID)) {
					raf = fd_raf.get(fd);
					raf.close();
					
					// use RPC call to upload a file from cache
					System.out.println("Local verID: " + local_verID + " (" + remote_verID + ")");
					System.out.println("uploading of oriPath: " + oriPath);
					
					BufferedInputStream reader = new 
					BufferedInputStream(new FileInputStream(localPath));
					FileInfo fi;

					// check if need chunking
					if (f.length() < MAX_LEN) {
						// just send the whole file
						byte buffer[] = new byte[(int) f.length()];
						reader.read(buffer, 0, buffer.length);
	
						fi = new FileInfo(oriPath, buffer, local_verID, false, f.length());
						server.setFile(fi);
					}
					else {
						// send an packet indicating will be using chunking
						fi = new FileInfo(oriPath, true, f.length());
						server.setFile(fi);

						// do chunking and send
						long file_len = f.length();
						long sent_len = 0;
						long send;

						while (sent_len < file_len) {
							send = Math.min(MAX_LEN, file_len - sent_len);
							byte buffer[] = new byte[(int) send];
							reader.read(buffer, 0, buffer.length);

							fi = new FileInfo(oriPath, buffer, local_verID, false, f.length());
							server.setChunk(fi, sent_len);
							sent_len += send;
						}
					}
					reader.close();
					fd_raf.remove(fd);
				}
				else {
					System.out.println("Local file didn't change. ");
				}

				fd_f.remove(fd);
				fd_mode.remove(fd);
				// Mark: move synchronized keyword to function
				avail_fds.add(fd);
	
				// Mark: implicit lock as function keyword && thread-safe type
				// update # of cache object users
				cache_user_count.put(localPath, cache_user_count.get(localPath) - 1);

			} catch (Exception e) {
				System.out.println("[close] Error: " + e.getMessage());
				e.printStackTrace();
			}

			print_cache();
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
			String oriPath, mode;
			RandomAccessFile raf;
			System.out.println("--[WRITE] called from " + fd);
			try {
				if (!fd_f.containsKey(fd))
					return Errors.EBADF;
	
				f = fd_f.get(fd);
				if (f.isDirectory())
					return Errors.EISDIR;
	
				mode = fd_mode.get(fd);
				if (mode == "r")
					return Errors.EBADF;

				raf = fd_raf.get(fd);
				long change = raf.getFilePointer() + buf.length - f.length();
	
				// use lock to ensure safely to unit operation on sizeCached
				synchronized (cache_lock) {
					sizeCached -= f.length();
					// before writing, check that if there's enough space in cache
					while (sizeCached + change > cachesize) {
						System.out.println("Not enough. sizeCache: " + sizeCached 
													+ "; file change:" + change);
						if (cache_evict() != 0)
							System.out.println("[open] Error occured in eviction");
					}
	
					// local execution for write
					raf.write(buf);
					sizeCached += f.length();
				}
			} catch (Exception e) {
				System.out.println("throw IO exception");
				return EIO;
			}

			oriPath = local2oriPath( copyPath2localPath(f.getPath()) );
			// update versionID
			// Mark: move synchronized keyword to function
			oriPath_verID.put(oriPath, oriPath_verID.get(oriPath) + 1);
			
			System.out.println("file " + oriPath + "'s verID update to " + oriPath_verID.get(oriPath));

			System.out.println("Write " + buf.length + " byte: " + buf);
			print_cache();
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
			print_cache();
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
			print_cache();
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
			String localPath = ori2localPath(path);
			File f;
			System.out.println("--[UNLINK] called from " + path);
			
			try {
				// delete the file from server
				rv = server.unlink(path);

				f = new File(localPath);
				if (f.exists()) {
					if (f.isFile() ) {
						// clear cache but leave copies
						synchronized (cache_lock) {
							sizeCached -= f.length();
		
							// delete the cache file
							if (!f.delete()) {
								System.out.println("[unlink] Error: delete file failed from " + 
																					localPath);
							}

							/* 
							 * keep the cache only when there's any opened writers (this case is 
							 * equal to a create_new). if there's only opened reader, we can remove
							 * it since there's no need to keep it (it's not evictable until autom-
							 * atically removed in the later close call).
							 * if it's not opened by anyone, just remove
							 */

							// check if the # of writers > 0
							if (!( (cache_user_count.getOrDefault(localPath, 0) > 0) &&
								(cache_user_count.getOrDefault(localPath, 0) > 
									 readerCount.getOrDefault(localPath, 0))) ) {
								LRU_cache.remove(localPath);
							}

							// if there's opening fd and cache_user_count, left them for close() to
							// deal with
						}
					}
					// otherwise, unlink to directory is not permitted. So do thing for this case.
				}
			} catch (Exception e) {
				System.out.println("[unlink] Error: " + e.getMessage());
				e.printStackTrace();
			}
			print_cache();
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
