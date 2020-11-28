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

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.Principal;
import java.util.Collections;
import java.util.Map;

import javax.security.auth.Subject;

import org.apache.karaf.jaas.boot.principal.UserPrincipal;
import org.apache.karaf.shell.ssh.KarafJaasAuthenticator;
import org.apache.sshd.common.file.FileSystemFactory;
import org.apache.sshd.common.file.root.RootedFileSystemProvider;
import org.apache.sshd.common.session.SessionContext;

// based on https://github.com/apache/karaf/tree/master/shell/ssh

public class SFTPHomeFileSystemFactory implements FileSystemFactory
{
	public  SFTPHomeFileSystemFactory(Map<String, ?> properties,Class<?>[] roleClasses)
	{
		super();
		this.roleClasses = roleClasses;
		this.refresh(properties);
	}
	
	public void refresh(Map<String, ?> properties)
	{
		this.homeRootPath = System.getProperty("karaf.base") + "/data/home";
		this.sftpHomeDirRoles = new String[] {"sftp"};
		this.sftpKarafRootRoles = new String[] {"admin"};
		
		if((properties.get("homeroot") != null) && (properties.get("homeroot") instanceof String) && (! ((String)properties.get("homeroot")).isEmpty()))
		{
			this.homeRootPath = (String)properties.get("homeroot");
		}
		
		String[] homeDirRoles = SFTPHomeSetup.getPropertyStringArray(properties, "rolehomedir");
		if((homeDirRoles != null) && (homeDirRoles.length > 0))
		{
			this.sftpHomeDirRoles = homeDirRoles;
		}
		
		String[] karafRootRoles = SFTPHomeSetup.getPropertyStringArray(properties, "rolekarafbasedir");
		if((karafRootRoles != null) && (karafRootRoles.length > 0))
		{
			this.sftpKarafRootRoles = karafRootRoles;
		}
	}
	
	
	
	private Class<?>[] roleClasses;
	
	private String[] sftpHomeDirRoles;
	private String[] sftpKarafRootRoles;
	private String homeRootPath;

	@Override
	public FileSystem createFileSystem(SessionContext session) throws IOException 
	{
		Path home = getUserHomeDir(session);
		
		if (Files.notExists(home)) 
		{
			Files.createDirectories(home); 
		}
		
		return new RootedFileSystemProvider().newFileSystem(home,Collections.emptyMap());
	}

	@Override
	public Path getUserHomeDir(SessionContext session) throws IOException 
	{
		boolean hasRoleHomeDir = false;
		boolean hasRoleKarafRootDir = false;
		final Subject subject = session.getAttribute(KarafJaasAuthenticator.SUBJECT_ATTRIBUTE_KEY);
		
		if(subject == null)
		{
			return null;
		}
		
		for (Principal principal : subject.getPrincipals()) 
		{
			for(Class<?> roleClass : this.roleClasses)
			{
				if (roleClass.isInstance(principal))
				{
					for(String sftpHomeDirRole : sftpHomeDirRoles)
					{
						if(sftpHomeDirRole.equals(principal.getName()))
						{
							hasRoleHomeDir = true;
						}
					}
					for(String sftpKarafRootRole : sftpKarafRootRoles)
					{
						if(sftpKarafRootRole.equals(principal.getName()))
						{
							hasRoleKarafRootDir = true;
						}
					}
				}
			}
		}
		
		if((! hasRoleHomeDir) && (! hasRoleKarafRootDir))
		{
			return null;
		}
		
		if(hasRoleKarafRootDir)
		{
			return Paths.get(System.getProperty("karaf.base"));
		}
		
		if(!subject.getPrincipals(UserPrincipal.class).iterator().hasNext())
		{
			return null;
		}
		
		String userName = subject.getPrincipals(UserPrincipal.class).iterator().next().getName();
		
		if((userName == null) || (userName.isEmpty()))
		{
			return null;
		}
		
		Path home = Paths.get(this.homeRootPath,userName);
		
		if (Files.notExists(home)) 
		{
			Files.createDirectories(home); 
		}
		
		return home;
	}

}
