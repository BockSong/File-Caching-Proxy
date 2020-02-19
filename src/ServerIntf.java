/* ServerInft.java */

import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.rmi.registry.*;
import java.rmi.Naming;

// Indicates remote interface description
public interface ServerIntf extends Remote {
             public String getInfo( FileInfo f )
                           throws RemoteException;
}
