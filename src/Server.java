/* Server.java */

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
    private static String rootdir;
    private static ConcurrentHashMap<String, Integer> oriPath_verID = new 
                                ConcurrentHashMap<String, Integer>();

    public Server() throws RemoteException {
           super(0);
    }

    private String getRemotepath( String path ) {
        // TODO: need to deal with corner case of path format
        return rootdir + "/" + path;
    }

    /*
     * getVersionID
     * This function returns the versionID of file at path. 
     * If it hasn't been requested, return 0;
     * If it doesn't exist, return -1
     */
    // TODO: may need synchronized key work here
    public int getVersionID( String path ) {
        if (oriPath_verID.containsKey(path)) {
            System.out.println("---" + path + " is in hashmap: " + oriPath_verID.get(path));
            return oriPath_verID.get(path);
        }
        else {
            System.out.println("---" + path + " hasn't been requested or doesn't exists. ");
            return 0;
        }
    }

    // client call this to download file
    public FileInfo getFile( String path )
            throws RemoteException {
        FileInfo fi;
        String remotePath = getRemotepath(path);
        System.out.println("[getFile] remotePath: " + remotePath);

        File file = new File(remotePath);
        if (!file.exists()) {
            // TODO: This branch should be eliminated now
            System.out.println("        this Dir does not exist. ");
            fi = new FileInfo(false, path);
        }
        else if (!file.isFile()) {
            System.out.println("        this is a directory. ");
            fi = new FileInfo(true, path);
        }
        else {
            byte buffer[] = new byte[(int) file.length()];
            try {
                BufferedInputStream input = new
                BufferedInputStream(new FileInputStream(remotePath));
                input.read(buffer, 0, buffer.length);
                input.close();
    
            } catch(Exception e) {
                System.out.println("Error in getFile: " + e.getMessage());
                e.printStackTrace();
            }
            // for the first time a client requested
            if (!oriPath_verID.containsKey(path)) {
                oriPath_verID.put(path, 0);
            }
            fi = new FileInfo(path, buffer, oriPath_verID.get(path));
            System.out.println("        file compressed successfully with ID "
                                                     + oriPath_verID.get(path));
        }

        return fi;
    }

    // client call this to upload file
    public void setFile( FileInfo fi )
            throws RemoteException {
        try {
            String remotePath = getRemotepath(fi.path);
            byte[] fi_data = fi.filedata;
            System.out.println("[setFile] remotePath: " + remotePath);
    
            File file = new File(remotePath);
            BufferedOutputStream output = new
            BufferedOutputStream(new FileOutputStream(remotePath));
            output.write(fi_data, 0, fi_data.length);
            output.flush();
            output.close();

			// update versionID once received the file
            oriPath_verID.put(fi.path, fi.versionID);
            System.out.println("        file loaded successfully with ID "
                                                        + fi.versionID);
        } catch (Exception e) {
            System.out.println("Error in setFile: " + e.getMessage());
            e.printStackTrace();
        }
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
		System.out.println("port: " + port + "\nrootdir: " + rootdir);

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
