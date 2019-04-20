/*
 *  Copyright (c) 2019 Sebastian Palarus
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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.security.Principal;
import java.util.Map;

import javax.security.auth.Subject;

import org.apache.karaf.shell.api.console.Session;
import org.apache.karaf.shell.api.console.SessionFactory;
import org.apache.karaf.shell.ssh.KarafJaasAuthenticator;
import org.apache.karaf.shell.ssh.SshTerminal;
import org.apache.karaf.shell.support.ShellUtil;
import org.apache.sshd.common.Factory;
import org.apache.sshd.server.Command;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SessionAware;
import org.apache.sshd.server.session.ServerSession;

// based on https://github.com/apache/karaf/tree/master/shell/ssh

public class SFTPHomeShellFactory implements Factory<Command> 
{
	private SessionFactory sessionFactory;
	private String[] sshRoles;
	private Class<?>[] roleClasses;

	public SFTPHomeShellFactory(SessionFactory sessionFactory,Map<String, ?> properties,Class<?>[] roleClasses)
	{
		this.sessionFactory = sessionFactory;
		this.roleClasses = roleClasses;
		this.refresh(properties);
	}
	
	public void refresh(Map<String, ?> properties)
	{
		this.sshRoles = new String[] {"sshconsole"};
		
		String[] secureShellRoles = SFTPHomeSetup.getPropertyStringArray(properties, "rolesecureshell");
		if((secureShellRoles != null) && (secureShellRoles.length > 0))
		{
			this.sshRoles = secureShellRoles;
		}
	}
	
	public Command create() 
	{
		return new ShellImpl();
	}
	
	public class ShellImpl implements Command, SessionAware 
	{
		private InputStream in;
		private OutputStream out;
		private OutputStream err;
		private ExitCallback callback;
		private ServerSession session;
		private Session shell;
		private SshTerminal terminal;
		private boolean closed;
		
		public void setInputStream(final InputStream in) 
		{
			this.in = in;
		}
		
		public void setOutputStream(final OutputStream out) 
		{
			this.out = out;
		}
		
		public void setErrorStream(final OutputStream err) 
		{
			this.err = err;
		}
		
		public void setExitCallback(ExitCallback callback) 
		{
			this.callback = callback;
		}

		public void setSession(ServerSession session) 
		{
			this.session = session;
		}
		
		public void start(final Environment env) throws IOException 
		{
			try
			{
				final Subject subject = ShellImpl.this.session != null ? ShellImpl.this.session.getAttribute(KarafJaasAuthenticator.SUBJECT_ATTRIBUTE_KEY) : null;
				if(subject != null)
				{
					boolean hasCorrectRole = false;
					for (Principal principal : subject.getPrincipals()) 
					{
						for (Class<?> roleClass : roleClasses) 
						{
							if (roleClass.isInstance(principal)) 
							{
								for(String role : sshRoles)
								{
									if(role.isEmpty())
									{
										continue;
									}
									if(role.equals(principal.getName()))
									{
										hasCorrectRole = true;
									}
								}
							}
						}
					}
					
					if(! hasCorrectRole)
					{
						destroy();
						return;
					}
				}
				
				String encoding = getEncoding(env);
				terminal = new SshTerminal(env, in, out, encoding);
				final PrintStream pout = new PrintStream(terminal.output(), true, encoding);
				final PrintStream perr = err instanceof PrintStream ? (PrintStream) err : out == err ? pout : new PrintStream(err, true, encoding);
				shell = sessionFactory.create(in, pout,perr, terminal, encoding, this::destroy);
				for (Map.Entry<String, String> e : env.getEnv().entrySet())
				{
					shell.put(e.getKey(), e.getValue());
				}
				JaasHelper.runAs(subject, () -> new Thread(shell, "Karaf ssh console user " + ShellUtil.getCurrentUserName()).start());
			} catch (Exception e) 
			{
				throw new IOException("Unable to start shell", e);
			}
		}
		
		public void destroy() 
		{
			if (!closed) 
			{
				closed = true;
				flush(out, err);
				close(in, out, err);
				callback.onExit(0);
			}
		}
	}
	
	/**
	 * Get the default encoding.  Will first look at the LC_CTYPE environment variable, then the input.encoding
	 * system property, then the default charset according to the JVM.
	 *
	 * @return The default encoding to use when none is specified.
	 */
	public static String getEncoding(Environment env) 
	{
		// LC_CTYPE is usually in the form en_US.UTF-8
		String ctype = env.getEnv().getOrDefault("LC_TYPE", System.getenv("LC_CTYPE"));
		String envEncoding = extractEncodingFromCtype(ctype);
		if (envEncoding != null) 
		{
			return envEncoding;
		}
		return System.getProperty("input.encoding", Charset.defaultCharset().name());
	}
	
	/**
	 * Parses the LC_CTYPE value to extract the encoding according to the POSIX standard, which says that the LC_CTYPE
	 * environment variable may be of the format <code>[language[_territory][.codeset][@modifier]]</code>
	 *
	 * @param ctype The ctype to parse, may be null
	 * @return The encoding, if one was present, otherwise null
	 */
	static String extractEncodingFromCtype(String ctype) 
	{
		if (ctype != null && ctype.indexOf('.') > 0) 
		{
			String encodingAndModifier = ctype.substring(ctype.indexOf('.') + 1);
			if (encodingAndModifier.indexOf('@') > 0) 
			{
				return encodingAndModifier.substring(0, encodingAndModifier.indexOf('@'));
			} 
			else 
			{
				return encodingAndModifier;
			}
		}
		return null;
	}
	
	private static void flush(OutputStream... streams) 
	{
		for (OutputStream s : streams) 
		{
			try 
			{
				s.flush();
			} 
			catch (IOException e) 
			{
				// Ignore
			}
		}
	}
	
	private static void close(Closeable... closeables) 
	{
		for (Closeable c : closeables) 
		{
			try 
			{
				c.close();
			} 
			catch (Exception e) 
			{
				// Ignore
			}
		}
	}
}