/* Server.java */

import java.io.*;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.*;

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject; import java.rmi.RemoteException;
import java.rmi.registry.*;
import java.rmi.Naming;

// indicates object that can be copied remotely
public class Box implements Serializable {
    public float width;
    public float height;
}

// Indicates remote interface description
public interface AreaIntf extends Remote {
             public float getArea( Box b )
                           throws RemoteException;
}

// Server object definition
public class Server extends UnicastRemoteObject implements AreaIntf {
    public Server() throws RemoteException {
           super(0);
    }

    public float getArea( Box b )
            throws RemoteException {
        return b.width * b.height;
    }

	public static void main(String[] args) throws IOException {
        int port;
        String rootdir;
		try {
			port = Integer.parseInt(args[0]);
		}
		catch (NumberFormatException e)
		{
			System.out.println("NumberFormatException in parsing args. \n");
			port = 15440;
		}
		rootdir = args[1];

        try { // create registry if it doesn’t exist
            LocateRegistry.createRegistry(port);
        }
        catch (RemoteException e) {
			System.out.println("RemoteException Catched!\n");
        }

        Server server = new Server(); 
        Naming.rebind("//127.0.0.1/Server", server);
        
		System.out.println("Server initialized. \n");
	}
}
