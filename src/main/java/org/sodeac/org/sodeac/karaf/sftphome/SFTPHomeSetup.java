/*
 *  Copyright (c) 2019, 2020 Sebastian Palarus
 *  
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *  under the License.
 */
package org.sodeac.org.sodeac.karaf.sftphome;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.karaf.shell.api.console.SessionFactory;
import org.apache.karaf.shell.ssh.ShellCommand;
import org.apache.sshd.common.file.FileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.scp.ScpCommandFactory;
import org.apache.sshd.server.shell.ShellFactory;
import org.apache.sshd.server.subsystem.SubsystemFactory;
import org.apache.sshd.server.subsystem.sftp.SftpSubsystemFactory;
import org.osgi.framework.ServiceReference;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

// based on https://github.com/apache/karaf/tree/master/shell/ssh

@Component
(
	immediate=true,
	configurationPid	= SFTPHomeSetup.SERVICE_PID, 
	configurationPolicy	= ConfigurationPolicy.REQUIRE,
	service= SFTPHomeSetup.class
)
public class SFTPHomeSetup 
{
	public static final String SERVICE_PID = "org.sodeac.org.sodeac.karaf.sftphome";
	
	@ObjectClassDefinition(name=SERVICE_PID, description="Configuration Setup SFTP",pid=SFTPHomeSetup.SERVICE_PID)
	interface Config
	{
		@AttributeDefinition(name="homeroot",description = "directory contains private homedirectories of users" , defaultValue="./data/home" ,type=AttributeType.STRING)
		String homeroot();
		
		@AttributeDefinition(name="rolesecureshell",description = "role for users with access to secure shell" , defaultValue="sshconsole" ,type=AttributeType.STRING)
		String rolesecureshell();
		
		@AttributeDefinition(name="rolehomedir",description = "role for users with private home directory" , defaultValue="sftphome" ,type=AttributeType.STRING)
		String rolehomedir();
		
		@AttributeDefinition(name="rolekarafbasedir",description = "role for users with access to karaf base directory" , defaultValue="admin" ,type=AttributeType.STRING)
		String rolekarafbasedir();
	}
	
	private SessionFactory sessionFactory = null;
	private Set<SshServer> pending = new HashSet<SshServer>();
	private Map<SshServer, ManagedSSHServer> managedServerIndex = new HashMap<SshServer, ManagedSSHServer>(); 
	
	protected ComponentContext context = null;
	protected Map<String, ?> properties = null;
	
	@Activate
	public void activate(ComponentContext context, Map<String, ?> properties) throws Exception
	{
		this.context = context;
		this.properties = properties;
		
		this.setupPendingServer();
		
	}
	
	@Deactivate
	public void deactivate(ComponentContext context) throws Exception 
	{
		this.properties = null;
		this.context = null;
		
		for(Entry<SshServer,ManagedSSHServer> entry : managedServerIndex.entrySet())
		{
			SshServer sshServer = entry.getKey();
			ManagedSSHServer managedSSHServer = entry.getValue();
			
			sshServer.setShellFactory(managedSSHServer.originalShellFactory);
			sshServer.setFileSystemFactory(managedSSHServer.originalFileSystemFactory);
			sshServer.setCommandFactory(managedSSHServer.originalCommandFactory);
			sshServer.setSubsystemFactories(managedSSHServer.originalSubsystemFactories);
		}
		this.managedServerIndex.clear();
		this.pending.clear();
	}
	
	@Modified 
	public void modified(Map<String, ?> properties) throws Exception
	{
		this.properties = properties;
		for(Entry<SshServer,ManagedSSHServer> entry : managedServerIndex.entrySet())
		{
			SshServer sshServer = entry.getKey();
			
			if(sshServer.getShellFactory() instanceof SFTPHomeShellFactory)
			{
				((SFTPHomeShellFactory)sshServer.getShellFactory()).refresh(this.properties);
			}
			if(sshServer.getFileSystemFactory() instanceof SFTPHomeFileSystemFactory)
			{
				((SFTPHomeFileSystemFactory)sshServer.getFileSystemFactory()).refresh(this.properties);
			}
		}
	}
	
	@Reference(cardinality=ReferenceCardinality.MULTIPLE,policy=ReferencePolicy.DYNAMIC)
	public void bindSessionFactory(ServiceReference<SessionFactory> serviceReference, SessionFactory sessionFactory)
	{
		if(! "org.apache.karaf.shell.core".equals(serviceReference.getBundle().getSymbolicName()))
		{
			return;
		}
		this.sessionFactory = sessionFactory;
		this.setupPendingServer();
	}
	
	public void unbindSessionFactory(ServiceReference<SessionFactory> serviceReference, SessionFactory sessionFactory) 
	{
		if(this.sessionFactory == sessionFactory)
		{
			this.sessionFactory = null;
			
			for(Entry<SshServer,ManagedSSHServer> entry : managedServerIndex.entrySet())
			{
				SshServer sshServer = entry.getKey();
				ManagedSSHServer managedSSHServer = entry.getValue();
				
				sshServer.setShellFactory(managedSSHServer.originalShellFactory);
				sshServer.setFileSystemFactory(managedSSHServer.originalFileSystemFactory);
				sshServer.setCommandFactory(managedSSHServer.originalCommandFactory);
				sshServer.setSubsystemFactories(managedSSHServer.originalSubsystemFactories);
				this.pending.add(sshServer);
			}
			managedServerIndex.clear();
		}
	}
	
	@Reference(cardinality=ReferenceCardinality.MULTIPLE,policy=ReferencePolicy.DYNAMIC)
	public void bindSshServer(ServiceReference<SshServer> serviceReference,SshServer sshServer)
	{
		if(! "org.apache.karaf.shell.ssh".equals(serviceReference.getBundle().getSymbolicName()))
		{
			return;
		}
		
		this.pending.add(sshServer);
		this.setupPendingServer();
        
	}
	
	private void setupPendingServer()
	{
		if(this.sessionFactory == null)
		{
			return;
		}
		
		if(this.properties == null)
		{
			return;
		}
		
		if((this.pending == null) || pending.isEmpty())
		{
			return;
		}
		
		for(SshServer sshServer : this.pending)
		{
			if(managedServerIndex.containsKey(sshServer))
			{
				continue;
			}
			
			ManagedSSHServer managedSSHServer = new ManagedSSHServer();
			managedSSHServer.originalShellFactory = sshServer.getShellFactory();
			managedSSHServer.originalFileSystemFactory = sshServer.getFileSystemFactory(); 
			managedSSHServer.originalCommandFactory = sshServer.getCommandFactory();
			managedSSHServer.originalSubsystemFactories = sshServer.getSubsystemFactories();
			
			sshServer.setShellFactory(new SFTPHomeShellFactory(sessionFactory, this.properties,new Class[] {org.apache.karaf.jaas.boot.principal.RolePrincipal.class}));
			sshServer.setFileSystemFactory(new SFTPHomeFileSystemFactory(this.properties,new Class[] {org.apache.karaf.jaas.boot.principal.RolePrincipal.class}));
			sshServer.setCommandFactory(new ScpCommandFactory.Builder().withDelegate((channel, cmd) -> new ShellCommand(sessionFactory, cmd)).build());
			sshServer.setSubsystemFactories(Collections.singletonList(new SftpSubsystemFactory()));
			
			managedServerIndex.put(sshServer, managedSSHServer);
		}
		this.pending.clear();
	}
	
	public void unbindSshServer(ServiceReference<SshServer> serviceReference,SshServer sshServer)
	{
		ManagedSSHServer managedSSHServer = this.managedServerIndex.get(sshServer);
		if(managedSSHServer == null)
		{
			return;
		}
		this.managedServerIndex.remove(sshServer);
		this.pending.remove(sshServer);
		
		sshServer.setShellFactory(managedSSHServer.originalShellFactory);
		sshServer.setFileSystemFactory(managedSSHServer.originalFileSystemFactory);
		sshServer.setCommandFactory(managedSSHServer.originalCommandFactory);
		sshServer.setSubsystemFactories(managedSSHServer.originalSubsystemFactories);
	}
	
	private class ManagedSSHServer
	{
		private ShellFactory originalShellFactory;
		private FileSystemFactory originalFileSystemFactory;
		private CommandFactory originalCommandFactory;
		private List<SubsystemFactory> originalSubsystemFactories;
	}
	
	protected static String[] getPropertyStringArray(Map<String, ?> properties, String key)
	{
		if(properties.get(key) == null)
		{
			return null;
		}
		
		if(properties.get(key) instanceof String)
		{
			String[] split = ((String)properties.get(key)).split("\\,");
			Set<String> values = new HashSet<String>();
			for(String item : split)
			{
				item = item.trim();
				if(!item.isEmpty())
				{
					values.add(item);
				}
			}
			return values.toArray(new String[values.size()]);
		}
		
		if(properties.get(key) instanceof String[])
		{
			String[] split =  (String[]) properties.get(key);
			Set<String> values = new HashSet<String>();
			for(String item : split)
			{
				item = item.trim();
				if(!item.isEmpty())
				{
					values.add(item);
				}
			}
			return values.toArray(new String[values.size()]);
		}
		
		return null;
	}
}
