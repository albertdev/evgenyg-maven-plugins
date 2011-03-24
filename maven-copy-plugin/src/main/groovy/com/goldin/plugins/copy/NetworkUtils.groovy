package com.goldin.plugins.copy


import static com.goldin.plugins.common.GMojoUtils.*
import com.goldin.gcommons.util.GroovyConfig
import com.goldin.org.apache.tools.ant.taskdefs.optional.net.FTP
import com.goldin.plugins.common.CustomAntBuilder
import com.goldin.plugins.common.GMojoUtils
import com.goldin.plugins.common.ThreadLocals
import org.apache.maven.plugin.logging.Log
import static com.goldin.plugins.common.GMojoUtils.devNullOutputStream
import static com.goldin.plugins.common.GMojoUtils.stars
import com.goldin.gcommons.beans.ExecOption


/**
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 * Various network related util methods
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 */
class NetworkUtils
{
    static Log getLog () { ThreadLocals.get( Log ) }


    /**
     * Downloads files required using remote location specified.
     *
     * @param resource        current copy resource
     * @param remotePath      remote location: http, ftp or scp URL.
     * @param targetDirectory directory to download the files to
     * @param verbose         verbose logging
     * @param groovyConfig    current Groovy configuration
     */
    static download ( CopyResource resource, String remotePath, File targetDirectory, boolean verbose, GroovyConfig groovyConfig )
    {
        verifyBean().notNull( resource )
        verifyBean().notNullOrEmpty( remotePath )
        assert netBean().isNet( remotePath )
        verifyBean().directory( targetDirectory )

        if ( netBean().isHttp( remotePath ))
        {
            httpDownload( targetDirectory, remotePath, verbose )
        }
        else if ( netBean().isFtp( remotePath ))
        {
            ftpDownload( targetDirectory, remotePath, resource, groovyConfig, verbose )
        }
        else if ( netBean().isScp( remotePath ))
        {
            scpDownload( targetDirectory, remotePath, verbose )
        }
        else
        {
            throw new RuntimeException( "Unrecognized download remote path [$remotePath]" )
        }
    }


    /**
     * Uploads files to remote paths specified.
     *
     * @param remotePaths    remote paths to upload files to
     * @param directory      files directory
     * @param includes       include patterns
     * @param excludes       exclude patterns
     * @param verbose        verbose logging
     * @param failIfNotFound whether execution should fail if not files were found
     */
    public static void upload ( String[]     remotePaths,
                                File         directory,
                                List<String> includes,
                                List<String> excludes,
                                boolean      verbose,
                                boolean      failIfNotFound )
    {
        assert remotePaths
        verifyBean().notNullOrEmpty( *remotePaths )
        verifyBean().directory( directory )

        for ( remotePath in remotePaths )
        {
            assert netBean().isNet( remotePath )

            if ( netBean().isHttp( remotePath ))
            {
                throw new RuntimeException( "HTTP upload is not implemented yet: http://evgeny-goldin.org/youtrack/issue/pl-312" )
            }

            for ( file in fileBean().files( directory, includes, excludes, true, false, failIfNotFound ))
            {
                if ( netBean().isScp( remotePath ))
                {
                    scpUpload( file, remotePath, verbose )
                }
                else if ( netBean().isFtp( remotePath ))
                {
                    ftpUpload( file, remotePath, verbose )
                }
                else
                {
                    throw new RuntimeException( "Unsupported remote path [$remotePath]" )
                }
            }
        }
    }


    /**
     * Downloads file from URL to directory specified.
     *
     * @param parentDirectory directory to store the file downloaded
     * @param httpUrl             URL to download the file
     * @return reference to file downloaded, stored in the directory specified
     *
     * @throws RuntimeException if fails to download the file
     */
    public static File httpDownload ( File    parentDirectory,
                                      String  httpUrl,
                                      boolean verbose )
    {
        fileBean().mkdirs( parentDirectory )
        assert ( netBean().isHttp( httpUrl ))

        BufferedInputStream  bis           = null
        BufferedOutputStream bos           = null
        String               fileName      = httpUrl.substring( httpUrl.lastIndexOf( '/' ) + 1 )
        File                 localFile     = new File( parentDirectory, fileName )

        log.info( "Downloading [$httpUrl] to [$localFile.canonicalPath]" )

        localFile.withOutputStream { OutputStream os ->  httpUrl.toURL().eachByte( 10240 ) { byte[] buffer, int bytes -> os.write( buffer, 0, bytes ) }}

        verifyBean().file( localFile )
        if ( verbose ) { log.info( "[$httpUrl] downloaded to [$localFile.canonicalPath]" )}
        localFile
    }


    /**
    * Executes "sshexec" using the command provided on the server specified
    */
    static void sshexec ( String  command,
                          String  username,
                          String  password,
                          String  host,
                          String  directory )
    {
        new AntBuilder().sshexec( command     : "cd ${ directory } ${ command }",
                                  host        : host,
                                  username    : username,
                                  password    : password,
                                  trust       : true,
                                  failonerror : true )
    }


    static void ftpDownload ( File         localDirectory,
                              String       remotePath,
                              CopyResource resource,
                              GroovyConfig groovyConfig,
                              boolean      verbose )
    {
        fileBean().mkdirs( localDirectory )
        assert resource.includes, "<include> or <includes> should be specified for FTP download"

        List<String>        includes = new ArrayList<String>( resource.includes )
        List<String>        excludes = new ArrayList<String>( resource.excludes )
        Map<String, String> ftpData  = netBean().parseNetworkPath( remotePath )
        String  remotePathLog        = "${ ftpData[ 'protocol' ] }://${ ftpData[ 'username' ] }@${ ftpData[ 'host' ] }${ ftpData[ 'directory' ] }"
        boolean isList               = ( resource.curl || resource.wget )
        def     commandParts         = ( resource.curl ?: resource.wget ?: null )?.split( /\|/ ) // "wget|ftp-list.txt|true|false"
        def     command              = ( isList ? commandParts[ 0 ] : null )
        def     listFile             = ( isList ? (( commandParts.size() > 1 ) ? new File ( commandParts[ 1 ] ) :
                                                                                 new File ( constantsBean().USER_DIR_FILE, 'ftp-list.txt' )) :
                                                  null )
        def     deleteListFile       = ( isList && commandParts.size() > 2 ) ? Boolean.valueOf( commandParts[ 2 ] ) : true
        def     nativeListing        = ( isList && commandParts.size() > 3 ) ? Boolean.valueOf( commandParts[ 3 ] ) : false
        def     listFilePath         = listFile?.canonicalPath
        def     localDirectoryPath   = localDirectory.canonicalPath
        long    t                    = System.currentTimeMillis()
        def     retryCount           = 1
        def     newList              = true
        def     previousList         = ''

        if ( listFile ) { fileBean().mkdirs( listFile.parentFile ) }

        if ( ftpData[ 'directory' ] != '/' )
        {   /**
             * If any of include or exclude patterns contain remote directory, it needs to be removed:
             * http://evgeny-goldin.org/youtrack/issue/pl-265
             */
            String remoteDirectory = ftpData[ 'directory' ].replaceAll( /^\/*/, '' )                    // Without leading slashes
            def c = { it.replace( remoteDirectory, '' ).replace( '\\', '/' ).replaceAll( /^\/*/, '' )}  // Remove remote directory
            if ( includes.any{ it.contains( remoteDirectory ) }) { includes = includes.collect( c ) }
            if ( excludes.any{ it.contains( remoteDirectory ) }) { excludes = excludes.collect( c ) }
        }

        while  ( true )
        {
            log.info( """
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Attempt [$retryCount]: downloading files from [$remotePathLog] to [$localDirectoryPath]
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
Include patterns  :
${ stars( includes ) }
Exclude patterns  :
${ stars( excludes ) }
External command  : [${ command?.trim()             ?: 'None' }]
List file         : [${ listFilePath?.trim()        ?: 'None' }]
List filter       : [${ resource.listFilter?.trim() ?: 'None' }]
Delete list file  : [$deleteListFile]
Native FTP listing: [$nativeListing]
Verbose           : [$verbose]
Number of retries : [$resource.retries]
Timeout           : [$resource.timeout] sec (${ resource.timeout.intdiv( constantsBean().SECONDS_IN_MINUTE ) } min)
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~""" )

            try
            {
                if ( isList && newList ) { listFile.write( '' ) } // Will be appended with data

                previousList = ( listFile?.isFile() ? listFile.text : '' )

                if ( isList && nativeListing )
                {
                    for ( file in netBean().listFiles( remotePath, includes, excludes, 1 ))
                    {
                        listFile.append( FTP.listSingleFile( ftpData[ 'host' ], file.path, file.size ) + "\n" )
                    }
                }
                else
                {
                    new CustomAntBuilder().ftp( action          : isList ? 'list' : 'get',
                                                listing         : listFile,
                                                listingAppend   : true,  // Custom property: to append a data to a listing file instead of overwriting it
                                                listingFullPath : true,  // Custom property: to list full FTP path of each file instead of default "raw listing"
                                                server          : ftpData[ 'host' ],
                                                userid          : ftpData[ 'username' ],
                                                password        : ftpData[ 'password' ],
                                                remotedir       : ftpData[ 'directory' ],
                                                verbose         : verbose,
                                                retriesAllowed  : resource.retries,
                                                passive         : true,
                                                binary          : true )
                    {
                        fileset( dir       : localDirectory.canonicalPath,
                                 includes  : includes.join( ',' ),
                                 excludes  : excludes.join( ',' ))
                    }
                }

                if ( isList )
                {
                    def listFileText  = verifyBean().file( listFile ).text
                    log.info( "List file is stored at [$listFilePath]:${ constantsBean().CRLF }${ listFileText }" )

                    /**
                     * Creating a Map of files: "file name" => "file size"
                     * Each line in a list file looks like
                     * "ftp://host.com//od/small/OfficersDirectors03_GL_f_20101120_1of1.xml.zip|23505456"
                     * {@link com.goldin.org.apache.tools.ant.taskdefs.optional.net.FTP#listFile}
                     */
                    Map listFileMap = listFileText.splitWith( 'eachLine', String ).inject( [:], {
                        Map map, String line ->

                        def    ( String ftpUrl, String fileSize ) = line.split( /\|/ )
                        assert ( ftpUrl && fileSize )

                        assert ( ! map.containsKey( ftpUrl )) : "[$ftpUrl] appears more than once in [$listFilePath]"
                        assert (( fileSize as Long ) > -1   ) : "[$ftpUrl] has a negative size [$fileSize] in [$listFilePath]"

                        map[ ftpUrl ] = fileSize as Long
                        map
                    })

                    if ( resource.listFilter )
                    {
                        def o        = GMojoUtils.groovy( resource.listFilter, Object, groovyConfig, 'files', listFileMap )
                        def filesSet = new HashSet( o as List<String> )                   // Set<String> of file names to include
                        listFileMap  = listFileMap.findAll{ filesSet.contains( it.key )}  // Filtering all Map entries with a Set

                        if ( verbose )
                        {
                            log.info( "Files to download after applying <listFilter>:${ constantsBean().CRLF }${ stars( listFileMap.keySet()) }" )
                        }
                    }

                    def nFiles = listFileMap.size()
                    log.info( "Downloading [$nFiles] file${ ( nFiles == 1 ) ? '' : 's' } to [$localDirectoryPath]" )

                    listFileMap.eachWithIndex {
                        String ftpUrl, long size, int index ->
                        download( ftpUrl, size, ftpData, localDirectory, resource.curl, command, verbose, resource.timeout, resource.retries, index, nFiles )
                    }

                    if ( deleteListFile ) { fileBean().delete( listFile ) }
                }

                long totalTimeMs  = ( System.currentTimeMillis() - t )
                log.info( "Attempt [$retryCount]: done, " +
                          "[${ totalTimeMs.intdiv( constantsBean().MILLIS_IN_SECOND ) }] sec "+
                          "(${ totalTimeMs.intdiv( constantsBean().MILLIS_IN_MINUTE ) } min)" )
                return
            }
            catch ( e )
            {
                log.info( "Attempt [$retryCount]: failed: $e", e )
                assert (( retryCount++ ) < resource.retries ), \
                       "Failed to download files from [$remotePathLog] to [$localDirectoryPath] after [${ retryCount - 1 }] attempt${ ( retryCount == 2 ) ? '' : 's' }"

                log.info( "Trying again .." )

                if ( listFile?.isFile() && ( ! nativeListing ))
                {   /**
                     * Files that are already listed are added to excludes pattern.
                     * Next listing attempt will append to listing file instead of overwriting it
                     */
                    newList         = false
                    def sessionList = listFile.splitWith( 'eachLine', String ) - previousList.splitWith( 'eachLine', String )
                    excludes       += sessionList.collect{ // "ftp://server//path/to/file|size" => "path/to/file"
                                                           it.replaceAll( /^ftp:\/\/[^\/]+\/+|\|\d+$/, '' ) }

                    log.info( "Files listed in this session are added to excludes: ${ constantsBean().CRLF }${ stars( sessionList ) }" )
                }
            }
        }
    }


    /**
     * Downloads the file specified
     */
    private static void download( String  ftpUrl,
                                  long    fileSize,
                                  Map     ftpData,
                                  File    localDirectory,
                                  String  curl,
                                  String  command,
                                  boolean verbose,
                                  long    timeoutSec,
                                  int     maxAttempts,
                                  int     fileIndex,
                                  int     totalFiles )
    {
        def fileName     = ftpUrl.substring( ftpUrl.lastIndexOf('/') + 1 )
        def destFile     = new File( localDirectory, fileName )
        def destFilePath = destFile.canonicalPath
        def exec         = curl ?
            "Not implemented yet" :
            "$command -S -${ verbose ? '' : 'n' }v -O \"$destFile\" -T 300 \"$ftpUrl\" --ftp-user=${ ftpData.username } --ftp-password=${ ftpData.password }"

        fileBean().delete( destFile )
        log.info( "[$ftpUrl] => [$destFilePath]: Started (file [${ fileIndex + 1 }] of [$totalFiles], " +
                  "[${ fileSize.intdiv( 1024 ) }] Kb)" )

        if ( verbose )
        {
            log.debug( "[${ exec.replace( ftpData.password, '*****' )}]" )
        }

        def sout = ( verbose ? System.out : devNullOutputStream())
        def serr = ( verbose ? System.err : devNullOutputStream())

        for ( def attempts = 1; ( attempts <= maxAttempts ); attempts++ )
        {
            fileBean().delete( destFile )
            long t           = System.currentTimeMillis()
            generalBean().execute( exec, ExecOption.CommonsExec, sout, serr, ( timeoutSec * constantsBean().MILLIS_IN_SECOND ), localDirectory )
            long fileSizeNow = verifyBean().file( destFile ).length()

            if ( fileSizeNow == fileSize )
            {
                log.info( "[$ftpUrl] => [$destFilePath]: Finished ([${ ( System.currentTimeMillis() - t ).intdiv( 1000 ) }] sec, " +
                          "file [${ fileIndex + 1 }] of [$totalFiles], " +
                          "[${ fileSizeNow.intdiv( 1024 ) }] Kb)" )
                return
            }
            else
            {
                log.info( "[$ftpUrl] => [$destFilePath]: file size [$fileSizeNow] doesn't match original [$fileSize], trying again .." )
            }
        }

        throw new RuntimeException( "Failed to download non-empty file after [$maxAttempts] attempts" )
    }


    static void ftpUpload ( File    file,
                            String  remotePath,
                            boolean verbose )
    {
        def data = netBean().parseNetworkPath( remotePath )
        assert 'ftp' == data[ 'protocol' ]
        verifyBean().notNullOrEmpty( data[ 'username' ], data[ 'password' ], data[ 'host' ], data[ 'directory' ])

        /**
         * http://evgeny-goldin.org/javadoc/ant/Tasks/ftp.html
         */
        new AntBuilder().ftp( action    : 'put',
                              server    : data[ 'host' ],
                              userid    : data[ 'username' ],
                              password  : data[ 'password' ],
                              remotedir : data[ 'directory' ],
                              verbose   : verbose,
                              passive   : true,
                              binary    : true )
        {
            fileset( file : file.canonicalPath )
        }
    }


    static void scpDownload ( File    localDirectory,
                              String  remotePath,
                              boolean verbose )
    {
        def data = netBean().parseNetworkPath( remotePath )
        assert 'scp' == data[ 'protocol' ]
        verifyBean().notNullOrEmpty( data[ 'username' ], data[ 'password' ], data[ 'host' ], data[ 'directory' ])

        /**
         * http://evgeny-goldin.org/javadoc/ant/Tasks/scp.html
         */
        new AntBuilder().scp( file     : "${ data[ 'username' ] }@${ data[ 'host' ] }:${ data[ 'directory' ] }",
                              todir    : localDirectory.canonicalPath,
                              password : data[ 'password' ],
                              verbose  : verbose,
                              trust    : true )
    }


   /**
    * Uploads file provided to scp location specified
    */
    static void scpUpload ( File    file,
                            String  remotePath,
                            boolean verbose )
    {
        def data = netBean().parseNetworkPath( remotePath )
        assert 'scp' == data[ 'protocol' ]
        verifyBean().notNullOrEmpty( data[ 'username' ], data[ 'password' ], data[ 'host' ], data[ 'directory' ])

        /**
         * http://evgeny-goldin.org/javadoc/ant/Tasks/scp.html
         */
        new AntBuilder().scp( file     : file.path,
                              todir    : "${ data[ 'username' ] }@${ data[ 'host' ] }:${ data[ 'directory' ] }",
                              password : data[ 'password' ],
                              verbose  : verbose,
                              trust    : true )
    }
}
