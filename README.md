# Karaf SFTP Home

This bundle enables private sftp home directories for various karaf users.

## Karaf dependencies
```
feature:install scr
```

## Install ( Apache Karaf 4.2.3-4.2.8 )

```
bundle:install -s mvn:org.sodeac/org.sodeac.karaf.sftphome/1.0.0
```

## Configuration

A valid OSGi configuration is requrired to reconfigure SshServer.

* homeroot          # directory contains private homedirectories
* rolesecureshell   # role for users with access to secureshell
* rolehomedir       # role for users with private home directory
* rolekarafbasedir  # role for users with access to karaf base directory

Additionally the **_sshRole_** defined in configuration **_org.apache.karaf.shell_** must assigned to users (by default **_ssh_**) !
Users with both roles ( defined in **_rolehomedir_** and **_rolekarafbasedir_** ) have access to karaf base directory.

## Example installation on vanilla Karaf 4.2.4 with PropertiesLoginModule

```
# install
feature:install scr
bundle:install -s mvn:org.sodeac/org.sodeac.karaf.sftphome/1.0.0

# create special role for sftp users with private home directory
jaas:realm-manage --index 1
jaas:group-create sftphomegroup
jaas:update

jaas:realm-manage --index 1
jaas:group-role-add sftphomegroup ssh
jaas:update

jaas:realm-manage --index 1
jaas:group-role-add sftphomegroup sftphome
jaas:update

# secure shell for admingroup
jaas:realm-manage --index 1
jaas:group-role-add admingroup sshconsole
jaas:update

# sftp access to ${karaf.base) for admingroup 
jaas:realm-manage --index 1
jaas:group-role-add admingroup sftpkaraf
jaas:update

# create user sftpuser with access to private home
jaas:realm-manage --index 1
jaas:user-add sftpuser secret
jaas:update

jaas:realm-manage --index 1
jaas:group-add sftpuser sftphomegroup
jaas:update

# configuration
config:edit org.sodeac.org.sodeac.karaf.sftphome
config:property-set homeroot "${karaf.base}/data/home"
config:property-set rolesecureshell sshconsole
config:property-set rolehomedir sftphome
config:property-set rolekarafbasedir sftpkaraf
config:update
```
Result:
* user **_karaf_** has sftp access to ${karaf.base} and can login to secure shell
* user **_sftpuser_** has sftp access to ${karaf.base}/data/home/sftpuser and can **not** login to secure shell

## Credits
 * [Apache Karaf](https://karaf.apache.org/)
 * [Apache MINA](https://mina.apache.org/)
