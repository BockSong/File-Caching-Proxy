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
    public Server() throws RemoteException {
           super(0);
    }

    public String getInfo( FileInfo f )
            throws RemoteException {
        return f.info;
    }

	public static void main(String[] args) throws IOException {
        int port;
        String rootdir; // directory tree populated with the initial files and subdirectories to serve.
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
