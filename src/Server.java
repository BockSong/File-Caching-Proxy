/* 
 *
 * Server.java 
 * 
 * Server class which implement session semantics.
 * 
 */

import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.*;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.registry.*;
import java.rmi.Naming;

// Server object definition
public class Server extends UnicastRemoteObject implements ServerIntf {
    // directory tree populated with the initial files and subdirectories to serve.
	public static final int ENOENT = -2;
	public static final int EISDIR = -21;
	public static final int MAX_LEN = 819200;
    private static String rootdir;
    private static ConcurrentHashMap<String, Integer> oriPath_verID = new 
                                ConcurrentHashMap<String, Integer>();

    public Server() throws RemoteException {
           super(0);
    }

    private String getRemotepath( String path ) {
        return rootdir + "/" + path;
    }

    /*
     * getVersionID
     * This function returns the versionID of file at path. 
     * If it hasn't been requested, return 0;
     * If it doesn't exist, return -1
     */
    public synchronized int getVersionID( String path ) {
        File file = new File(getRemotepath(path));
        if (!file.exists()) {
            return -1;
        }
        if (oriPath_verID.containsKey(path)) {
            return oriPath_verID.get(path);
        }
        else {
            return 0;
        }
    }

    // client call this to download a specfic chunk
    public synchronized FileInfo getChunk( String path, long offset )
            throws RemoteException {
        FileInfo fi;
        String remotePath = getRemotepath(path);
        try {
            File file = new File(remotePath);
            long send_len = Math.min(MAX_LEN, file.length() - offset);
			RandomAccessFile reader = new RandomAccessFile(remotePath, "r");
            byte buffer[] = new byte[(int) send_len];

            reader.seek(offset);
            reader.read(buffer);
            reader.close();
            fi = new FileInfo(path, buffer, oriPath_verID.get(path), true, file.length());
        } catch(Exception e) {
            System.out.println("[getChunk] Error: " + e.getMessage());
            e.printStackTrace();
            fi = null;
        }
        return fi;
    }

    // client call this to download file
    public synchronized FileInfo getFile( String path )
            throws RemoteException {
        FileInfo fi;
        String remotePath = getRemotepath(path);

        try {
            File file = new File(remotePath);
            if (!file.exists()) {
                // This branch shouldn't be executed until an error happens
                fi = null;
            }
            else if (!file.isFile()) {
                fi = new FileInfo(path, false, 0, -1);
            }
            else {
                // for the first time a file is requested, record its versionID
                if (!oriPath_verID.containsKey(path)) {
                    oriPath_verID.put(path, 0);
                }
                if (file.length() < MAX_LEN) {
                    // just send the whole file
                    byte buffer[] = new byte[(int) file.length()];
                    BufferedInputStream reader = new
                    BufferedInputStream(new FileInputStream(remotePath));
                    reader.read(buffer, 0, buffer.length);
                    reader.close();

                    fi = new FileInfo(path, buffer, oriPath_verID.get(path), false, file.length());
                }
                else {
                    // send an empty fi indicating will be using chunking
                    fi = new FileInfo(path, true, file.length(), oriPath_verID.get(path));
                }
            }
        } catch(Exception e) {
            System.out.println("[getFile] Error: " + e.getMessage());
            e.printStackTrace();
            fi = null;
        }
        return fi;
    }

    // client call this to upload chunk
    public synchronized void setChunk( FileInfo fi, long offset )
            throws RemoteException {
        try {
            String remotePath = getRemotepath(fi.path);
            byte[] fi_data = fi.filedata;
    
            File file = new File(remotePath);
			RandomAccessFile writer = new RandomAccessFile(remotePath, "rw");
            writer.seek(offset);
            writer.write(fi_data);
            writer.close();
        } catch (Exception e) {
            System.out.println("[setChunk] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // client call this to upload file
    public synchronized void setFile( FileInfo fi )
            throws RemoteException {
        try {
            String remotePath = getRemotepath(fi.path);
            byte[] fi_data = fi.filedata;
    
            File file = new File(remotePath);
            File Dir = new File(remotePath.substring(0, remotePath.lastIndexOf("/")) );
			// If its directory doesn't exist, create it first
			if ( !Dir.exists() && !Dir.mkdirs() ) {
				System.out.println("[setFile] Error: unable to make new directory in cache!");
            };
            
			// update versionID once received the file
            oriPath_verID.put(fi.path, fi.versionID);

            // if not chunking ,write it directly. otherwise write in setChunk()
            if (!fi.isChunking) {
                BufferedOutputStream writer = new
                BufferedOutputStream(new FileOutputStream(remotePath));
                writer.write(fi_data, 0, fi_data.length);
                writer.flush();
                writer.close();
            }
        } catch (Exception e) {
            System.out.println("[setFile] Error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // client call this to unlink a file
    public synchronized int unlink( String path )
            throws RemoteException {
        try {
            String remotePath = getRemotepath(path);
    
            File file = new File(remotePath);
			if (!file.exists())
				return ENOENT;
			if (file.isDirectory())
				return EISDIR;

            if (!file.delete()) {
                System.out.println("Error: delete file failed from " + remotePath);
            }
            
			// delete its pair in hashmap
            oriPath_verID.remove(path);
        } catch (Exception e) {
            System.out.println("Error in setFile: " + e.getMessage());
            e.printStackTrace();
        }
        return 0;
    }

	public static void main(String[] args) throws IOException {
		// check args number
        if(args.length != 2) {
            System.out.println("Usage: java Server port rootdir");
            System.exit(0);
        }

        int port;
		try {
			port = Integer.parseInt(args[0]);
		}
		catch (NumberFormatException e)
		{
			System.out.println("NumberFormatException in parsing args.");
			port = 15440;
		}
		rootdir = args[1];

        try { // create registry if it doesn't exist
            LocateRegistry.createRegistry(port);
        }
        catch (RemoteException e) {
			System.out.println("RemoteException Catched!");
        }

        Server server = new Server(); 
        // either rebind or bind should work
        Naming.rebind("//localhost:" + port + "/ServerIntf", server);
        
		System.out.println("Server initialized. ");
	}
}
