package com.firedrum.mojo.schema;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.Reader;
import java.io.StringReader;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.Vector;

import org.apache.commons.text.StringEscapeUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.shared.filtering.MavenFileFilter;
import org.apache.maven.shared.filtering.MavenFileFilterRequest;
import org.apache.maven.shared.filtering.MavenFilteringException;
import org.apache.maven.shared.scriptinterpreter.RunFailureException;
import org.apache.maven.shared.scriptinterpreter.ScriptRunner;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringUtils;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcher;
import org.sonatype.plexus.components.sec.dispatcher.SecDispatcherException;

/**
 * Executes Schema migration SQL against a database.
 */
@Mojo( name = "migrate", defaultPhase = LifecyclePhase.DEPLOY, requiresProject = true, threadSafe = true )
public class MigrateMojo extends AbstractMojo {

	/**
	 * Call {@link #setOnError(String)} with this value to abort SQL command execution if an error is found.
	 */
	public static final String ON_ERROR_ABORT = "abort";

	/**
	 * Call {@link #setOnError(String)} with this value to continue SQL command execution until all commands have been
	 * attempted, then abort the build if an SQL error occurred in any of the commands.
	 */
	public static final String ON_ERROR_ABORT_AFTER = "abortAfter";

	/**
	 * Call {@link #setOnError(String)} with this value to continue SQL command execution if an error is found.
	 */
	public static final String ON_ERROR_CONTINUE = "continue";

	/**
	 * Call {@link #setOrderFile(String)} with this value to sort in ascendant order the sql files.
	 */
	public static final String FILE_SORTING_ASC = "ascending";

	/**
	 * Call {@link #setOrderFile(String)} with this value to sort in descendant order the sql files.
	 */
	public static final String FILE_SORTING_DSC = "descending";

	/**
	 * The default SQL delimiter which is used to separate statements.
	 */
	public static final String DEFAULT_DELIMITER = ";";

	//////////////////////////// User Info ///////////////////////////////////

	/**
	 * Database username. If not given, it will be looked up through <code>settings.xml</code>'s server with
	 * <code>${settingsKey}</code> as key.
	 *
	 * @since 1.0.0
	 */
	@Parameter( property = "username" )
	private String username;

	/**
	 * Database password. If not given, it will be looked up through <code>settings.xml</code>'s server with
	 * <code>${settingsKey}</code> as key.
	 *
	 * @since 1.0.0
	 */
	@Parameter( property = "password" )
	private String password;

	/**
	 * Ignore the password and use anonymous access. This may be useful for databases like MySQL which do not allow
	 * empty password parameters in the connection initialization.
	 *
	 * @since 1.0.0
	 */
	@Parameter( defaultValue = "false" )
	private boolean enableAnonymousPassword;

	/**
	 * Additional key=value pairs separated by comma to be passed into JDBC driver.
	 *
	 * @since 1.0.0
	 */
	@Parameter( defaultValue = "", property = "driverProperties" )
	private String driverProperties;

	/**
	 * @since 1.0.0
	 */
	@Parameter( defaultValue = "${settings}", readonly = true, required = true )
	private Settings settings;

	/**
	 * Server's <code>id</code> in <code>settings.xml</code> to look up username and password. Defaults to
	 * <code>${url}</code> if not given.
	 *
	 * @since 1.0.0
	 */
	@Parameter( property = "settingsKey" )
	private String settingsKey;

	/**
	 * MNG-4384
	 * 
	 * @since 1.0.0
	 */
	@Component( role = org.sonatype.plexus.components.sec.dispatcher.SecDispatcher.class, hint = "default" )
	private SecDispatcher securityDispatcher;

	/**
	 * Skip execution when there is an error obtaining a connection. This is a special case to support databases, such
	 * as embedded Derby, that can shutdown the database via the URL (i.e. <code>shutdown=true</code>).
	 *
	 * @since 1.0.0
	 */
	@Parameter( defaultValue = "false", property = "skipOnConnectionError" )
	private boolean skipOnConnectionError;

	/**
	 * Skip missing files defined by {@link #setSrcFiles(File[])}. This behavior allows to define a full fledged list
	 * of all sql files in a single {@code pom.xml} without failing if used by modules for which some sql files are not
	 * available on the classpath.
	 */
	@Parameter( defaultValue = "false", property = "skipMissingFiles")
	private boolean skipMissingFiles;

	/**
	 * Setting this parameter to <code>true</code> will force the execution of this mojo, even if it would get skipped
	 * usually.
	 */
	@Parameter( defaultValue = "false", property = "forceOpenJpaExecution", required = true )
	private boolean forceMojoExecution;

	/**
	 * The Maven Project Object
	 */
	@Parameter( defaultValue = "${project}", readonly = true, required = true )
	private MavenProject project;

	/**
	 */
	@Parameter( defaultValue = "${session}", readonly = true, required = true )
	private MavenSession mavenSession;

	//////////////////////////////// Source info /////////////////////////////

	/**
	 * The current schema version number.
	 *
	 * @since 1.0.0
	 */
	@Parameter( property="schema.version" )
	private String version;

	private long liveVersion;

	@Parameter( required = true )
	private String isUpgradedSql;

	@Parameter( required = true )
	private String isInitializedSql;

	@Parameter( required = true )
	private String getLiveVersionSql;

	/**
	 * When <code>true</code>, skip the execution.
	 *
	 * @since 1.0.0
	 */
	@Parameter( defaultValue = "false" )
	private boolean skip;

	////////////////////////////////// Database info /////////////////////////

	/**
	 * Database URL.
	 *
	 * @since 1.0.0
	 */
	@Parameter( property = "url", required = true )
	private String url;

	/**
	 * Database driver classname.
	 *
	 * @since 1.0.0
	 */
	@Parameter( property = "driver", required = true )
	private String driver;

	////////////////////////////// Operation Configuration ////////////////////

	/**
	 * Action to perform if an error is found. Possible values are <code>abort</code> and <code>continue</code>.
	 *
	 * @since 1.0.0
	 */
	@Parameter( defaultValue = ON_ERROR_ABORT, property = "onError" )
	private String onError;

	////////////////////////////// Parser Configuration ////////////////////

	/**
	 * Set the delimiter that separates SQL statements.
	 *
	 * @since 1.0.0
	 */
	@Parameter( defaultValue = DEFAULT_DELIMITER, property = "delimiter" )
	private String delimiter;

	/**
	 * <p>
	 * The delimiter type takes two values - "normal" and "row". Normal means that any occurrence of the delimiter
	 * terminate the SQL command whereas with row, only a line containing just the delimiter is recognized as the end of
	 * the command.
	 * </p>
	 * <p>
	 * For example, set this to "go" and delimiterType to "row" for Sybase ASE or MS SQL Server.
	 * </p>
	 *
	 * @since 1.0.0
	 */
	@Parameter( defaultValue = DelimiterType.NORMAL, property = "delimiterType" )
	private String delimiterType;

	/**
	 * Keep the format of an SQL block.
	 *
	 * @since 1.0.0
	 */
	@Parameter( defaultValue = "false", property = "keepFormat" )
	private boolean keepFormat;

	///////////////////////////////////////////////////////////////////////////////////////
	/**
	 * Print SQL results.
	 *
	 * @since 1.0.0
	 */
	@Parameter( defaultValue = "false", property = "printResultSet" )
	private boolean printResultSet;

	/**
	 * Print header columns.
	 */
	private boolean showheaders = true;

	/**
	 * Dump the SQL execution's output to a file.<br />
	 * <strong>Default value is</strong>: <code>System.out</code>.
	 *
	 * @since 1.0.0
	 */
	@Parameter
	private File outputFile;

	/**
	 * The delimiter used to separate fields in the output when using <code>printResultSet</code>.
	 *
	 * @since 1.0.0
	 */
	@Parameter( defaultValue = "," )
	private String outputDelimiter;

	/**
	 * Encoding to use when reading SQL statements from a file.
	 *
	 * @since 1.0.0
	 */
	@Parameter( defaultValue = "${project.build.sourceEncoding}", property = "encoding" )
	private String encoding;

	/**
	 * Append to an existing file or overwrite it?
	 */
	private boolean append = false;

	/**
	 * Argument to Statement.setEscapeProcessing If you want the driver to use regular SQL syntax then set this to
	 * false.
	 * 
	 * @since 1.0.0
	 */
	@Parameter( defaultValue = "true", property = "escapeProcessing" )
	private boolean escapeProcessing;

	////////////////////////////////// Internal properties//////////////////////

	/**
	 * number of successful executed statements
	 */
	private int successfulStatements = 0;

	/**
	 * number of total executed statements
	 */
	private int totalStatements = 0;

	/**
	 * Database connection
	 */
	private Connection conn = null;

	/**
	 * SQL statement
	 */
	private Statement statement = null;

	/**
	 * SQL transactions to perform
	 */
	private List<Transaction> transactions = new Vector<>();

	/**
	 * @since 1.0.0
	 */
	@Component( role = org.apache.maven.shared.filtering.MavenFileFilter.class )
	private MavenFileFilter fileFilter;

	/**
	 * Set to true if you want to filter the srcFiles using system-, user- and project properties
	 * 
	 * @since 1.0.0
	 */
	@Parameter( defaultValue = "false", property = "enableFiltering" )
	private boolean enableFiltering;

	private ScriptRunner scriptRunner;

	/**
	 * @since 1.0.0
	 */
	@Parameter
	private File preExecuteHookScript;

	/**
	 * @since 1.0.0
	 */
	@Parameter
	private File postExecuteHookScript;

	/**
	 * Add a SQL transaction to execute
	 * 
	 * @return a new SqlExecMojo.Transaction
	 */
	public Transaction createTransaction() {
		Transaction t = new Transaction();
		transactions.add( t );
		return t;
	}

	/**
	 * Set the file encoding to use on the SQL files read in
	 *
	 * @param encoding the encoding to use on the files
	 */
	public void setEncoding(String encoding) {
		this.encoding = encoding;
	}

	/**
	 * Set the delimiter that separates SQL statements. Defaults to &quot;;&quot;;
	 * 
	 * @param delimiter the new delimiter
	 */
	public void setDelimiter(String delimiter) {
		this.delimiter = delimiter;
	}

	/**
	 * Set the delimiter type: "normal" or "row" (default "normal").
	 * 
	 * @param delimiterType the new delimiterType
	 */
	public void setDelimiterType(String delimiterType) {
		this.delimiterType = delimiterType;
	}

	/**
	 * Print result sets from the statements; optional, default false
	 * 
	 * @param print <code>true</code> to print the resultset, otherwise <code>false</code>
	 */
	public void setPrintResultSet(boolean print) {
		this.printResultSet = print;
	}

	/**
	 * Print headers for result sets from the statements; optional, default true.
	 * 
	 * @param showheaders <code>true</code> to show the headers, otherwise <code>false</code>
	 */
	public void setShowheaders(boolean showheaders) {
		this.showheaders = showheaders;
	}

	/**
	 * Set the output file;
	 * 
	 * @param output the output file
	 */
	public void setOutputFile(File output) {
		this.outputFile = output;
	}

	/**
	 * whether output should be appended to or overwrite an existing file. Defaults to false.
	 * 
	 * @param append <code>true</code> to append, otherwise <code>false</code> to overwrite
	 */
	public void setAppend(boolean append) {
		this.append = append;
	}

	/**
	 * whether or not format should be preserved. Defaults to false.
	 *
	 * @param keepformat {@code true} to keep the format {@code false} otherwise.
	 */
	public void setKeepFormat(boolean keepformat) {
		this.keepFormat = keepformat;
	}

	/**
	 * Set escape processing for statements.
	 * 
	 * @param enable <code>true</code> to escape, otherwise <code>false</code>
	 */
	public void setEscapeProcessing(boolean enable) {
		escapeProcessing = enable;
	}

	/**
	 * <p>
	 * Determine if the mojo execution should get skipped.
	 * </p>
	 * This is the case if:
	 * <ul>
	 * <li>{@link #skip} is <code>true</code></li>
	 * <li>if the mojo gets executed on a project with packaging type 'pom' and {@link #forceMojoExecution} is
	 * <code>false</code></li>
	 * </ul>
	 * 
	 * @return <code>true</code> if the mojo execution should be skipped.
	 */
	protected boolean skipMojo() {
		if ( skip ) {
			getLog().info( "User has requested to skip execution." );
			return true;
		}

		if ( !forceMojoExecution && project != null && "pom".equals( project.getPackaging() ) ) {
			getLog().info( "Skipping sql execution for project with packaging type 'pom'" );
			return true;
		}

		return false;
	}

	/**
	 * Load the sql file and then execute it
	 * 
	 * @throws MojoExecutionException
	 */
	public void execute() throws MojoExecutionException {
		if ( skipMojo() ) {
			return;
		}

		executeScriptPlusSql();
	}

	private void executeScriptPlusSql() throws MojoExecutionException {
		// prepare scriptrunner
		scriptRunner = new ScriptRunner( getLog() );
		scriptRunner.setScriptEncoding( encoding );

		Map<String, Object> context = new HashMap<>();

		try {
			if ( preExecuteHookScript != null ) {
				scriptRunner.run( "Schema-Maven-Plugin pre-execute script", preExecuteHookScript, context, null, "pre-execute", false );
			}

			executeSqlCore();
		} catch ( RunFailureException e ) {
			throw new MojoExecutionException( e.getMessage(), e );
		} catch ( IOException e ) {
			throw new MojoExecutionException( e.getMessage(), e );
		} finally {
			try {
				if ( postExecuteHookScript != null ) {
					scriptRunner.run( "Schema-Maven-Plugin post-execute script", postExecuteHookScript, context, null, "post-execute", false );
				}
			} catch ( RunFailureException e ) {
				throw new MojoExecutionException( e.getMessage(), e );
			} catch ( IOException e ) {
				throw new MojoExecutionException( e.getMessage(), e );
			}
		}
	}

	protected void executeSqlCore() throws MojoExecutionException {
		successfulStatements = 0;

		totalStatements = 0;

		loadUserInfoFromSettings();

		try {
			conn = getConnection();
		} catch ( SQLException e ) {
			if ( !this.skipOnConnectionError ) {
				throw new MojoExecutionException( e.getMessage(), e );
			} else {
				// error on get connection and user asked to skip the rest
				return;
			}
		}

		try {
			statement = conn.createStatement();
			statement.setEscapeProcessing( escapeProcessing );

			PrintStream out = System.out;
			try {
				if ( outputFile != null ) {
					getLog().debug( "Opening PrintStream to output file " + outputFile );
					outputFile.getParentFile().mkdirs();
					out = new PrintStream( new BufferedOutputStream( new FileOutputStream( outputFile.getAbsolutePath(), append ) ) );
				}

				long schemaVersion = 0;
				try {
					schemaVersion = Long.valueOf( version );
				} catch (NumberFormatException e) {
					// ignore
				}

				ResultSet resultSet = null;
				try {
					resultSet = statement.executeQuery( isUpgradedSql );
					if ( resultSet.next() ) {
						resultSet.close();

						resultSet = statement.executeQuery( getLiveVersionSql );
						if ( resultSet.next() ) {
							liveVersion = resultSet.getLong( 1 );
						} else {
							liveVersion = -1;
						}
					} else {
						resultSet.close();

						resultSet = statement.executeQuery( isInitializedSql );
						if ( resultSet.next() ) {
							liveVersion = 0;
						} else {
							liveVersion = -1;
						}
					}
				} catch ( SQLException e ) {
					getLog().error( "Failed to determine live schema version" );
					if ( ON_ERROR_ABORT.equalsIgnoreCase( getOnError() ) ) {
						throw e;
					}
					getLog().error( e.toString() );
				} finally {
					if ( resultSet != null ) {
						resultSet.close();
					}
				}

				if ( schemaVersion == liveVersion ) {
					getLog().info( "Schema Modification: Nothing has changed." );
				} else {
					MavenFileFilterRequest request = new MavenFileFilterRequest();
					request.setEncoding( encoding );
					request.setMavenSession( mavenSession );
					request.setMavenProject( project );
					request.setFiltering( enableFiltering );

					long counter = liveVersion;
					String src = null;
					if ( schemaVersion > liveVersion ) {
						if ( liveVersion == -1 ) {
							getLog().info( "Automatic Schema Modification: Performing initial database setup." );
						} else {
							getLog().info( "Automatic Schema Modification: Upgrading database..." );
						}

						while ( counter < schemaVersion ) {
							counter++;
							src = "schema/" + counter + "/upgrade.sql";

							File sourceFile = new File( src );
							if ( !sourceFile.exists() ) {
								throw new MojoExecutionException( sourceFile.getPath() + " not found." );
							}

							String basename = FileUtils.basename( sourceFile.getName() );
							String extension = FileUtils.extension( sourceFile.getName() );
							File targetFile = FileUtils.createTempFile( basename, extension, null );
							if ( !getLog().isDebugEnabled() ) {
								targetFile.deleteOnExit();
							}

							request.setFrom( sourceFile );
							request.setTo( targetFile );

							try {
								fileFilter.copyFile( request );
							} catch ( MavenFilteringException e ) {
								throw new MojoExecutionException( e.getMessage() );
							}

							Transaction t = createTransaction()
								.setLog( "Schema Modification: Executing " + src )
								.setSrc( targetFile );

							if ( counter > 1 ) {
								t.addText( "UPDATE `APPLICATION_SCHEMA` SET VERSION = " + counter );
							}
						}

						// Process all transactions
						for ( Transaction t : transactions ) {
							t.runTransaction( out );

							getLog().debug( "Committing transaction" );
							conn.commit();
						}

						if ( liveVersion == -1 ) {
							getLog().info( "Schema Modification: Initial database setup complete. Deployed schema v" + schemaVersion + "." );
						} else {
							getLog().info( "Schema Modification: Upgraded database from v" + liveVersion + " to v" + schemaVersion + "." );
						}
					} else {
						getLog().info( "Schema Modification: Downgrading database..." );
						while ( counter > schemaVersion ) {
							counter--;
							src = "schema/" + counter + "/downgrade.sql";

							File sourceFile = new File( src );
							if ( !sourceFile.exists() ) {
								throw new MojoExecutionException( sourceFile.getPath() + " not found." );
							}

							String basename = FileUtils.basename( sourceFile.getName() );
							String extension = FileUtils.extension( sourceFile.getName() );
							File targetFile = FileUtils.createTempFile( basename, extension, null );
							if ( !getLog().isDebugEnabled() ) {
								targetFile.deleteOnExit();
							}

							request.setFrom( sourceFile );
							request.setTo( targetFile );

							try {
								fileFilter.copyFile( request );
							} catch ( MavenFilteringException e ) {
								throw new MojoExecutionException( e.getMessage() );
							}

							Transaction t = createTransaction()
								.setLog( "Schema Modification: Executing " + src )
								.setSrc( targetFile );

							if ( counter > 0 ) {
								t.addText( "UPDATE `APPLICATION_SCHEMA` SET VERSION = " + counter );
							}
						}

						// Process all transactions
						for ( Transaction t : transactions ) {
							t.runTransaction( out );

							getLog().debug( "Committing transaction" );
							conn.commit();
						}

						getLog().info( "Schema Modification: Downgraded database from v" + liveVersion + " to v" + schemaVersion + "." );
					}
				}
			} finally {
				if ( out != null && out != System.out ) {
					out.close();
				}
			}
		} catch ( IOException e ) {
			throw new MojoExecutionException( e.getMessage(), e );
		} catch ( SQLException e ) {
			if ( conn != null && ON_ERROR_ABORT.equalsIgnoreCase( getOnError() ) ) {
				try {
					conn.rollback();
				} catch ( SQLException ex ) {
					// ignore
				}
			}
			throw new MojoExecutionException( e.getMessage(), e );
		} finally {
			try {
				if ( statement != null ) {
					statement.close();
				}
				if ( conn != null ) {
					conn.close();
				}
			} catch ( SQLException ex ) {
				// ignore
			}
		}

		getLog().info( getSuccessfulStatements() + " of " + getTotalStatements() + " SQL statements executed successfully" );

		if ( ON_ERROR_ABORT_AFTER.equalsIgnoreCase( getOnError() ) && totalStatements != successfulStatements ) {
			throw new MojoExecutionException( "Some SQL statements failed to execute" );
		}
	}

	/**
	 * Load username password from settings if user has not set them in JVM properties
	 * 
	 * @throws MojoExecutionException
	 */
	private void loadUserInfoFromSettings() throws MojoExecutionException {
		if ( this.settingsKey == null ) {
			this.settingsKey = getUrl();
		}

		if ( ( getUsername() == null || getPassword() == null ) && ( settings != null ) ) {
			Server server = this.settings.getServer( this.settingsKey );

			if ( server != null ) {
				if ( getUsername() == null ) {
					setUsername( server.getUsername() );
				}

				if ( getPassword() == null && server.getPassword() != null ) {
					try {
						setPassword( securityDispatcher.decrypt( server.getPassword() ) );
					} catch ( SecDispatcherException e ) {
						throw new MojoExecutionException( e.getMessage() );
					}
				}
			}
		}

		if ( getUsername() == null ) {
			// allow empty username
			setUsername( "" );
		}

		if ( getPassword() == null ) {
			// allow empty password
			setPassword( "" );
		}
	}

	/**
	 * Creates a new Connection as using the driver, url, userid and password specified. The calling method is
	 * responsible for closing the connection.
	 *
	 * @return Connection the newly created connection.
	 * @throws MojoExecutionException if the UserId/Password/Url is not set or there is no suitable driver or the driver
	 *             fails to load.
	 * @throws SQLException if there is problem getting connection with valid url
	 */
	private Connection getConnection() throws MojoExecutionException, SQLException {
		getLog().debug( "connecting to " + getUrl() );
		Properties info = new Properties();
		info.put( "user", getUsername() );

		if ( !enableAnonymousPassword ) {
			info.put( "password", getPassword() );
		}

		info.putAll( this.getDriverProperties() );

		Driver driverInstance = null;

		try {
			Class<?> dc = Class.forName( getDriver() );
			driverInstance = (Driver) dc.newInstance();
		} catch ( ClassNotFoundException e ) {
			throw new MojoExecutionException( "Driver class not found: " + getDriver(), e );
		} catch ( Exception e ) {
			throw new MojoExecutionException( "Failure loading driver: " + getDriver(), e );
		}

		Connection connection = driverInstance.connect( getUrl(), info );

		if ( connection == null ) {
			// Driver doesn't understand the URL
			throw new SQLException( "No suitable Driver for " + getUrl() );
		}

		connection.setAutoCommit( false );
		return connection;
	}

	/**
	 * parse driverProperties into Properties set
	 * 
	 * @return the driver properties
	 * @throws MojoExecutionException
	 */
	protected Properties getDriverProperties() throws MojoExecutionException {
		Properties properties = new Properties();

		if ( !StringUtils.isEmpty( this.driverProperties ) ) {
			String[] tokens = StringUtils.split( this.driverProperties, "," );
			for ( int i = 0; i < tokens.length; ++i ) {
				String[] keyValueTokens = StringUtils.split( tokens[i].trim(), "=" );
				if ( keyValueTokens.length != 2 ) {
					throw new MojoExecutionException( "Invalid JDBC Driver properties: " + this.driverProperties );
				}

				properties.setProperty( keyValueTokens[0], keyValueTokens[1] );
			}
		}

		return properties;
	}

	/**
	 * read in lines and execute them
	 * 
	 * @param reader the reader
	 * @param out the outputstream
	 * @throws SQLException
	 * @throws IOException
	 */
	private void runStatements(Reader reader, PrintStream out) throws SQLException, IOException {
		String line;

		// TODO: Check if this equivalent with if (enableBlockMode) {..
		if ( delimiterType.equals( DelimiterType.ROW ) ) {
			// no need to parse the content, ship it directly to jdbc in one sql statement
			line = IOUtil.toString( reader );
			execSQL( line, out );
			return;
		}

		StringBuffer sql = new StringBuffer();

		BufferedReader in = new BufferedReader( reader );

		int overflow = SqlSplitter.NO_END;

		while ( ( line = in.readLine() ) != null ) {
			if ( !keepFormat ) {
				line = line.trim();
			}

			if ( !keepFormat ) {
				if ( line.startsWith( "//" ) ) {
					continue;
				}
				if ( line.startsWith( "--" ) ) {
					continue;
				}
				StringTokenizer st = new StringTokenizer( line );
				if ( st.hasMoreTokens() ) {
					String token = st.nextToken();
					if ( "REM".equalsIgnoreCase( token ) ) {
						continue;
					}
				}
			}

			if ( !keepFormat ) {
				sql.append( " " ).append( line );
			} else {
				sql.append( "\n" ).append( line );
			}

			overflow = SqlSplitter.containsSqlEnd( line, delimiter, overflow );

			// SQL defines "--" as a comment to EOL
			// and in Oracle it may contain a hint
			// so we cannot just remove it, instead we must end it
			if ( !keepFormat && overflow == SqlSplitter.NO_END ) {
				sql.append( "\n" );
			}

			if ( ( delimiterType.equals( DelimiterType.NORMAL ) && overflow > 0 ) || ( delimiterType.equals( DelimiterType.ROW ) && line.trim().equals( delimiter ) ) ) {
				execSQL( sql.substring( 0, sql.length() - delimiter.length() ), out );
				sql.setLength( 0 ); // clean buffer
				overflow = SqlSplitter.NO_END;
			}
		}

		// Catch any statements not followed by ;
		if ( sql.length() > 0 ) {
			execSQL( sql.toString(), out );
		}
	}

	/**
	 * Exec the sql statement.
	 * 
	 * @param sql query to execute
	 * @param out the outputstream
	 */
	private void execSQL(String sql, PrintStream out) throws SQLException {
		// Check and ignore empty statements
		if ( "".equals( sql.trim() ) ) {
			return;
		}

		ResultSet resultSet = null;
		try {
			totalStatements++;
			getLog().debug( "SQL: " + sql );

			boolean ret;
			int updateCountTotal = 0;

			ret = statement.execute( sql );
			do {
				if ( !ret ) {
					int updateCount = statement.getUpdateCount();
					if ( updateCount != -1 ) {
						updateCountTotal += updateCount;
					}
				} else {
					resultSet = statement.getResultSet();
					if ( printResultSet ) {
						printResultSet( resultSet, out );
					}
				}
				ret = statement.getMoreResults();
			} while ( ret );

			getLog().debug( updateCountTotal + " rows affected" );

			if ( printResultSet ) {
				StringBuffer line = new StringBuffer();
				line.append( updateCountTotal ).append( " rows affected" );
				out.println( line );
			}

			SQLWarning warning = conn.getWarnings();
			while ( warning != null ) {
				getLog().debug( warning + " sql warning" );
				warning = warning.getNextWarning();
			}
			conn.clearWarnings();
			successfulStatements++;
		} catch ( SQLException e ) {
			getLog().error( "Failed to execute: " + sql );
			if ( ON_ERROR_ABORT.equalsIgnoreCase( getOnError() ) ) {
				throw e;
			}
			getLog().error( e.toString() );
		} finally {
			if ( resultSet != null ) {
				resultSet.close();
			}
		}
	}

	/**
	 * print any results in the result set.
	 * 
	 * @param rs the resultset to print information about
	 * @param out the place to print results
	 * @throws SQLException on SQL problems.
	 */
	private void printResultSet(ResultSet rs, PrintStream out) throws SQLException {
		if ( rs != null ) {
			getLog().debug( "Processing new result set." );
			ResultSetMetaData md = rs.getMetaData();
			int columnCount = md.getColumnCount();
			StringBuffer line = new StringBuffer();
			if ( showheaders ) {
				boolean first = true;
				for ( int col = 1; col <= columnCount; col++ ) {
					String columnValue = md.getColumnName( col );

					if ( columnValue != null ) {
						columnValue = columnValue.trim();

						if ( ",".equals( outputDelimiter ) ) {
							columnValue = StringEscapeUtils.escapeCsv( columnValue );
						}
					}

					if ( first ) {
						first = false;
					} else {
						line.append( outputDelimiter );
					}
					line.append( columnValue );
				}
				out.println( line );
				line = new StringBuffer();
			}
			while ( rs.next() ) {
				boolean first = true;
				for ( int col = 1; col <= columnCount; col++ ) {
					String columnValue = rs.getString( col );
					if ( columnValue != null ) {
						columnValue = columnValue.trim();

						if ( ",".equals( outputDelimiter ) ) {
							columnValue = StringEscapeUtils.escapeCsv( columnValue );
						}
					}

					if ( first ) {
						first = false;
					} else {
						line.append( outputDelimiter );
					}
					line.append( columnValue );
				}
				out.println( line );
				line = new StringBuffer();
			}
		}
		out.println();
	}

	/**
	 * Contains the definition of a new transaction element. Transactions allow several files or blocks of statements to
	 * be executed using the same JDBC connection and commit operation in between.
	 */
	private class Transaction {
		private File tSrcFile = null;

		private String tSqlCommand = "";

		private String tLog = "";

		/**
		 *
		 */
		public Transaction setSrc(File src) {
			this.tSrcFile = src;
			return this;
		}

		/**
		 *
		 */
		public Transaction addText(String sql) {
			this.tSqlCommand += sql;
			return this;
		}

		/**
		 *
		 */
		public Transaction setLog(String log) {
			this.tLog += log;
			return this;
		}

		/**
		 *
		 */
		private void runTransaction(PrintStream out) throws IOException, SQLException {
			if ( tLog.length() != 0 ) {
				getLog().info( tLog );
			}

			if ( tSrcFile != null ) {
				Reader reader = null;

				if ( StringUtils.isEmpty( encoding ) ) {
					reader = new FileReader( tSrcFile );
				} else {
					reader = new InputStreamReader( new FileInputStream( tSrcFile ), encoding );
				}

				try {
					runStatements( reader, out );
				} finally {
					reader.close();
				}
			}

			if ( tSqlCommand.length() != 0 ) {
				runStatements( new StringReader( tSqlCommand ), out );
			}
		}
	}

	//
	// helper accessors for unit test purposes
	//

	public String getUsername() {
		return this.username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return this.password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

	public String getUrl() {
		return this.url;
	}

	public void setUrl(String url) {
		this.url = url;
	}

	public String getDriver() {
		return this.driver;
	}

	public void setDriver(String driver) {
		this.driver = driver;
	}

	/**
	 * Number of SQL statements executed so far that caused errors.
	 *
	 * @return the number
	 */
	public int getSuccessfulStatements() {
		return successfulStatements;
	}

	/**
	 * Number of SQL statements executed so far, including the ones that caused errors.
	 *
	 * @return the number
	 */
	public int getTotalStatements() {
		return totalStatements;
	}

	public String getOnError() {
		return this.onError;
	}

	public void setOnError(String action) {
		if ( ON_ERROR_ABORT.equalsIgnoreCase( action ) ) {
			this.onError = ON_ERROR_ABORT;
		} else if ( ON_ERROR_CONTINUE.equalsIgnoreCase( action ) ) {
			this.onError = ON_ERROR_CONTINUE;
		} else if ( ON_ERROR_ABORT_AFTER.equalsIgnoreCase( action ) ) {
			this.onError = ON_ERROR_ABORT_AFTER;
		} else {
			throw new IllegalArgumentException( action + " is not a valid value for onError, only '" + ON_ERROR_ABORT + "', '" + ON_ERROR_ABORT_AFTER + "', or '" + ON_ERROR_CONTINUE + "'." );
		}
	}

	void setSettings(Settings settings) {
		this.settings = settings;
	}

	void setSettingsKey(String key) {
		this.settingsKey = key;
	}

	void setSkip(boolean skip) {
		this.skip = skip;
	}

	public void setSkipMissingFiles(boolean skipMissingFiles) {
		this.skipMissingFiles = skipMissingFiles;
	}

	public void setDriverProperties(String driverProperties) {
		this.driverProperties = driverProperties;
	}

	public List<Transaction> getTransactions() {
		return transactions;
	}

	public void setTransactions(List<Transaction> transactions) {
		this.transactions = transactions;
	}

	public void setFileFilter(MavenFileFilter filter) {
		this.fileFilter = filter;
	}

	public void setSecurityDispatcher(SecDispatcher securityDispatcher) {
		this.securityDispatcher = securityDispatcher;
	}
}
