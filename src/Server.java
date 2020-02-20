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

    public Server() throws RemoteException {
           super(0);
    }

    private String getRemotepath( String path ) {
        return rootdir + "/" + path;
    }

    // client call this to download file
    public FileInfo getFile( String path )
            throws RemoteException {
        try {
            FileInfo f = new FileInfo();
            f.path = path;

            File file = new File(path);
            byte buffer[] = new byte[(int) file.length()];
            BufferedInputStream input = new
            BufferedInputStream(new FileInputStream(path));
            input.read(buffer, 0, buffer.length);
            input.close();
            f.filedata = buffer;

            return f;
        } catch(Exception e) {
            System.out.println("getFile: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    // client call this to upload file
    public void setFile( FileInfo f )
            throws RemoteException {
        try {
            String remotePath = getRemotepath(f.path);
            byte[] f_data = f.filedata;
    
            File file = new File(remotePath);
            BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(file.getName()));
            output.write(f_data, 0, f_data.length);
            output.flush();
            output.close();
            
        } catch (Exception e) {
            System.out.println("setFile: " + e.getMessage());
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
		System.out.println("port: " + port + "\nrootdir: " + rootdir);
		rootdir = args[1];

        try { // create registry if it doesn’t exist
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
