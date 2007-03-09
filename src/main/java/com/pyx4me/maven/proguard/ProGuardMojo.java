package com.pyx4me.maven.proguard;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.maven.archiver.MavenArchiveConfiguration;
import org.apache.maven.archiver.MavenArchiver;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.apache.tools.ant.DefaultLogger;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.taskdefs.Java;
import org.codehaus.plexus.archiver.jar.JarArchiver;

/**
 * 
 * <p>
 *  The Obfuscate task provides a standalone obfuscation task
 * </p>
 * 
 * @goal proguard
 * @phase package
 * @description Create small jar files using ProGuard
 * @requiresDependencyResolution compile
 */

public class ProGuardMojo extends AbstractMojo {

	/**
	 * Recursively reads configuration options from the given file filename 
	 * 
	 * @parameter default-value="${basedir}/proguard.conf"
	 */
	private File proguardInclude;

	/**
	 * Specifies not to obfuscate the input class files. 
	 * 
	 * @parameter default-value="true"
	 */
	private boolean obfuscate; 

	/**
	 * Specifies that project compile dependencies be added as -libraryjars to proguard arguments 
	 * 
	 * @parameter default-value="true"
	 */	
	private boolean includeDependancy;
	
	
	/**
	 * Bundle project dependency to resulting jar. Specifies list of artifact inclusions
	 * 
	 * @parameter
	 */
	private Assembly assembly;

	
	/**
	 * Additional -libraryjars e.g. ${java.home}/lib/rt.jar 
	 * Project compile dependency are added automaticaly. See exclusions
	 * 
	 * @parameter
	 */
	private List libs;
	
	/**
	 * List of dependency exclusions
	 * 
	 * @parameter
	 */
	private List exclusions;
	
	/**
	 * Specifies the input jar name (or wars, ears, zips) of the application to be processed. 
	 * 
	 * @parameter expression="${project.build.finalName}.jar"
	 * @required
	 */
	protected String injar;

	/**
	 * Specifies the names of the output jars. 
	 * If attach the value ignored and anme constructed base on classifier
	 * If empty  input jar would be overdriven.
	 * 
	 * @parameter
	 */
	protected String outjar;

    /**
     * Specifies whether or not to attach the created artifact to the project
     *
     * @parameter default-value="false"
     */
    private boolean attach = false;

    /**
     * Specifies attach artifact type
     *
     * @parameter default-value="jar"
     */
    private String attachArtifactType;

    /**
     * Specifies attach artifact Classifier
     *
     * @parameter default-value="small"
     */
    private String attachArtifactClassifier;
    
    /**
     * Set to false to exclude the attachArtifactClassifier from the Artifact final name. Default value is true.
     *
     * @parameter default-value="true"
     */
    private boolean appendClassifier;
    
	/**
	 * Directory containing the input and generated JAR.
	 *
	 * @parameter expression="${project.build.directory}"
	 * @required
	 */
	protected File outputDirectory;

	/**
	 * The Maven project reference where the plugin is currently being executed.
	 * The default value is populated from maven.
	 *
	 * @parameter expression="${project}"
	 * @readonly
	 * @required
	 */
	protected MavenProject mavenProject;

	/**
	 * The plugin dependencies.
	 *
	 * @parameter expression="${plugin.artifacts}"
	 * @required
	 * @readonly
	 */
	protected List pluginArtifacts;

    /**
     * @component
     */
    private MavenProjectHelper projectHelper;
	
    /**
     * The Jar archiver.
     *
     * @parameter expression="${component.org.codehaus.plexus.archiver.Archiver#jar}"
     * @required
     */
    private JarArchiver jarArchiver;
    
    /**
     * The maven archive configuration to use.
     *
     * @parameter
     */
    protected MavenArchiveConfiguration archive = new MavenArchiveConfiguration();
    
	private Log log;

	static final String proguardMainClass = "proguard.ProGuard";

	/**
	 * ProGuard docs:
	 * Names with special characters like spaces and parentheses must be quoted with single or double quotes. 
	 */
	private static String fileNameToString(String fileName) {
		return "'" + fileName + "'";
	}
	
	private static String fileToString(File file) {
		return fileNameToString(file.toString());
	}
	
	private boolean useArtifactClassifier() {
		return appendClassifier && ((attachArtifactClassifier != null) && (attachArtifactClassifier.length() > 0));
	}
	
	public void execute() throws MojoExecutionException, MojoFailureException {

		log = getLog();

		boolean mainIsJar = mavenProject.getPackaging().equals("jar");
		boolean mainIsPom = mavenProject.getPackaging().equals("pom");
		
		File inJarFile = new File(outputDirectory, injar);
		if (mainIsJar && (!inJarFile.exists())) {
			throw new MojoFailureException("Can't find file " + inJarFile);
		}
		
		if (!outputDirectory.exists()) {
			if (!outputDirectory.mkdirs()) {
				throw new MojoFailureException("Can't create " + outputDirectory);
			}
		}
		
		File outJarFile;
		boolean sameArtifact;
		
		if (attach) {
			outjar = nameNoType(injar);
			if (useArtifactClassifier()) {
				outjar += "-" + attachArtifactClassifier;
			}
			outjar += "." + attachArtifactType;
		}
		
		if ((outjar != null) && (!outjar.equals(injar))) {
			sameArtifact = false;
			outJarFile = (new File(outputDirectory, outjar)).getAbsoluteFile();
		} else {
			sameArtifact = true;
			outJarFile = inJarFile.getAbsoluteFile();
			File baseFile = new File(outputDirectory, nameNoType(injar) + "_proguard_base.jar");
			if (baseFile.exists()) {
				if (!baseFile.delete()) {
					throw new MojoFailureException("Can't delete " + baseFile);
				}
			}
			if (inJarFile.exists()) {
				if (!inJarFile.renameTo(baseFile)) {
					throw new MojoFailureException("Can't rename " + inJarFile);
				}
			}
			inJarFile = baseFile;
		}

		ArrayList args = new ArrayList();
		
		if (log.isDebugEnabled()) {
			List dependancy = mavenProject.getCompileArtifacts();
			for (Iterator i = dependancy.iterator(); i.hasNext();) {
				Artifact artifact = (Artifact) i.next();
				log.debug("--- compile artifact:" + artifact.getArtifactId() + " " + artifact.getClassifier() + " " + artifact.getScope());
			}
		}
		
		Set inPath = new HashSet();
		boolean hasInclusionLibrary = false;
		if (assembly != null) {
			for (Iterator iter = assembly.inclusions.iterator(); iter.hasNext();) {
				Inclusion inc = (Inclusion) iter.next();
				if (!inc.library) {
					args.add("-injars");
					File file = getClasspathElement(getDependancy(inc, mavenProject), mavenProject);
					inPath.add(file.toString());
					log.debug("--- ADD JAR:" + inc.artifactId);
					StringBuffer filter = new StringBuffer(fileToString(file));
					filter.append("(!META-INF/MANIFEST.MF");
					if (inc.filter != null) {
						filter.append(",").append(inc.filter);
					} 
					filter.append(")");
					args.add(filter.toString());
				} else {
					hasInclusionLibrary = true;
				}
			}
		}

		if ((!mainIsPom) && inJarFile.exists()) {
			args.add("-injars");
			args.add(fileToString(inJarFile));
		}
		args.add("-outjars");
		args.add(fileToString(outJarFile));

		if (!obfuscate) {
			args.add("-dontobfuscate");
		}
		
		if (proguardInclude != null) {
			if (proguardInclude.exists()) {
				args.add("-include");
				args.add(fileToString(proguardInclude));
				log.debug("proguardInclude " + proguardInclude);
			} else {
				log.debug("proguardInclude config does not exists " + proguardInclude);
			}
		}

		if (includeDependancy) {
			List dependancy = this.mavenProject.getCompileArtifacts();
			for (Iterator i = dependancy.iterator(); i.hasNext();) {
				Artifact artifact = (Artifact) i.next();
				// dependancy filter
				if (isExclusion(artifact)) {
					continue;
				}
				File file;
				if (artifact.getClassifier() == null) {
				    // Conver to path if artifact is module
					file = getClasspathElement(artifact, mavenProject);
				} else {
					file = artifact.getFile();
				}
				
				if (inPath.contains(file.toString())) {
					log.debug("--- Ignore JAR:" + artifact.getArtifactId());
					continue;
				}
				log.debug("--- LIBRARY JAR:" + artifact.getArtifactId());
				args.add("-libraryjars");
				args.add(fileToString(file));
			}
		}
		
		if (libs != null) {
			for (Iterator i = libs.iterator(); i.hasNext();) {
				Object lib = i.next();
				args.add("-libraryjars");
				args.add(fileNameToString(lib.toString()));
			}
		}

		args.add("-printmapping");
		args.add(fileToString((new File(outputDirectory, "proguard_map.txt").getAbsoluteFile())));


		args.add("-printseeds");
		args.add(fileToString((new File(outputDirectory, "proguard_seeds.txt").getAbsoluteFile())));

		
		if (log.isDebugEnabled()) {
			args.add("-verbose");
		}

		log.info("execute ProGuard " + args.toString());
		proguardMain(getProguardJar(this), args, this);
		
		if ((assembly != null) && (hasInclusionLibrary)) {
		
			log.info("creating assembly");
			
			File baseFile = new File(outputDirectory, nameNoType(injar) + "_proguard_result.jar");
			if (baseFile.exists()) {
				if (!baseFile.delete()) {
					throw new MojoFailureException("Can't delete " + baseFile);
				}
			}
			File archiverFile = outJarFile.getAbsoluteFile();
			if (!outJarFile.renameTo(baseFile)) {
				throw new MojoFailureException("Can't rename " + outJarFile);
			}
			
			MavenArchiver archiver = new MavenArchiver();
			archiver.setArchiver(jarArchiver);
			archiver.setOutputFile(archiverFile);
			
			try {
				jarArchiver.addArchivedFileSet(baseFile);
				
				for (Iterator iter = assembly.inclusions.iterator(); iter.hasNext();) {
					Inclusion inc = (Inclusion) iter.next();
					if (inc.library) {
						File file; 
						Artifact artifact = getDependancy(inc, mavenProject);
						if (artifact.getClassifier() == null) {
							file = getClasspathElement(artifact, mavenProject);
						} else {
							file = artifact.getFile();
						}
						if (file.isDirectory()) {
							getLog().info("merge project: " + artifact.getArtifactId() + " " + file);
							jarArchiver.addDirectory(file);
						} else {
							getLog().info("merge artifact: " + artifact.getArtifactId());
							jarArchiver.addArchivedFileSet(file);
						}
					}
				}
				
				archiver.createArchive(mavenProject, archive);
				
			} catch (Exception e) {
				throw new MojoExecutionException("Unable to create jar", e);
			}
			
		}
		
		if (attach && !sameArtifact) {
			if (useArtifactClassifier()) {
				projectHelper.attachArtifact(mavenProject, attachArtifactType, attachArtifactClassifier, outJarFile);
			} else {
				projectHelper.attachArtifact(mavenProject, attachArtifactType, null, outJarFile);
			}
		}
	}

	static boolean isVersionGrate(Artifact artifact1, Artifact artifact2) {
		if ((artifact2 == null) || (artifact2.getVersion() == null)) {
			return true;
		}
		if ((artifact1 == null) || (artifact1.getVersion() == null)) {
			return false;
		}
		// Just very simple 
		return (artifact1.getVersion().compareTo(artifact2.getVersion()) > 0);
	}

	private static File getProguardJar(ProGuardMojo mojo) throws MojoExecutionException {

		Artifact proguardArtifact = null;
		// This should be solved in Maven 2.1
		for (Iterator i = mojo.pluginArtifacts.iterator(); i.hasNext();) {
			Artifact artifact = (Artifact) i.next();
			if ("proguard".equals(artifact.getArtifactId())) {
				if (isVersionGrate(artifact, proguardArtifact)) {
					proguardArtifact = artifact;
				}
			}
			mojo.getLog().debug("pluginArtifact: " + artifact.getFile());
		}
		if (proguardArtifact != null) {
			mojo.getLog().debug("proguardArtifact: " + proguardArtifact.getFile());
			return proguardArtifact.getFile().getAbsoluteFile();
		}
		mojo.getLog().info("proguard jar not found in pluginArtifacts");

		ClassLoader cl;
		cl = mojo.getClass().getClassLoader();
		//cl = Thread.currentThread().getContextClassLoader();
		String classResource = "/" + proguardMainClass.replace('.', '/') + ".class";
		URL url = cl.getResource(classResource);
		if (url == null) {
			throw new MojoExecutionException("Obfuscation failed ProGuard (" + proguardMainClass
					+ ") not found in classpath");
		}
		String proguardJar = url.toExternalForm();
		if (proguardJar.startsWith("jar:file:")) {
			proguardJar = proguardJar.substring("jar:file:".length());
			proguardJar = proguardJar.substring(0, proguardJar.indexOf('!'));
		} else {
			throw new MojoExecutionException("Unrecognized location (" + proguardJar + ") in classpath");
		}
		return new File(proguardJar);
	}

	private static void proguardMain(File proguardJar, ArrayList argsList, ProGuardMojo mojo)
			throws MojoExecutionException {

		Java java = new Java();

		Project antProject = new Project();
		antProject.setName(mojo.mavenProject.getName());
		antProject.init();
		
		DefaultLogger antLogger = new DefaultLogger();
        antLogger.setOutputPrintStream( System.out );
        antLogger.setErrorPrintStream( System.err );
        antLogger.setMessageOutputLevel(mojo.log.isDebugEnabled() ? Project.MSG_DEBUG : Project.MSG_INFO );

        antProject.addBuildListener( antLogger );
        antProject.setBaseDir( mojo.mavenProject.getBasedir() );
		
		java.setProject(antProject);
		java.setTaskName("proguard");

		mojo.getLog().info("proguard jar: " + proguardJar);

		java.createClasspath().setLocation(proguardJar);
		//java.createClasspath().setPath(System.getProperty("java.class.path"));
		java.setClassname(proguardMainClass);

		java.setFailonerror(true);

		java.setFork(true);

		for (Iterator i = argsList.iterator(); i.hasNext();) {
			java.createArg().setValue(i.next().toString());
		}

		int result = java.executeJava();
		if (result != 0) {
			throw new MojoExecutionException("Obfuscation failed (result=" + result + ")");
		}
	}

	private static String nameNoType(String artifactname) {
		return artifactname.substring(0, artifactname.lastIndexOf('.'));
	}
	
	private static Artifact getDependancy(Inclusion inc, MavenProject mavenProject) throws MojoExecutionException {
		List dependancy = mavenProject.getCompileArtifacts();
		for (Iterator i = dependancy.iterator(); i.hasNext();) {
			Artifact artifact = (Artifact) i.next();
			if (inc.match(artifact)) {
				return artifact;
			}
		}
		throw new MojoExecutionException("artifactId Not found " + inc.artifactId);
	}
	
	private boolean isExclusion(Artifact artifact) {
		if (exclusions == null) {
			return false;	
		}
		for (Iterator iter = exclusions.iterator(); iter.hasNext();) {
			Exclusion excl = (Exclusion) iter.next();
			if (excl.match(artifact)) {
				return true;
			}
		}
		return false;
	}
	
	private static File getClasspathElement(Artifact artifact, MavenProject mavenProject) throws MojoExecutionException {
		String refId = artifact.getGroupId() + ":" + artifact.getArtifactId();
        MavenProject project = (MavenProject) mavenProject.getProjectReferences().get( refId );
        if (project != null) {
			return new File(project.getBuild().getOutputDirectory());
		} else {
			File file = artifact.getFile();
			if ((file == null) || (!file.exists())) {
				throw new MojoExecutionException("Dependency Resolution Required " + artifact);
			}
			return file;
		}
	}
}
