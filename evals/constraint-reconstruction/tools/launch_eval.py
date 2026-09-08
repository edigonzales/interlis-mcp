#!/usr/bin/env python3
"""Codex connector launcher only. Builds, pins and execs the server; never speaks MCP."""
import fcntl, json, os, pathlib, shutil, subprocess, sys, tempfile
from benchmark import ROOT, BASE, read, write, sha, digest, verify_dependencies, build_sources

def main():
    suite=BASE/'v2';manifest=suite/'dependencies/manifest.json'
    verify_dependencies(manifest)
    home=pathlib.Path(os.environ.get('CODEX_HOME',str(pathlib.Path.home()/'.codex')))
    cache=home/'automations/interlis-mcp-constraint-benchmark/artifacts';cache.mkdir(parents=True,exist_ok=True)
    with (cache/'build.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        # The lock serializes connector starts without holding the benchmark run lock.
        source=build_sources()
        subprocess.run([str(ROOT/'gradlew'),'--quiet','bootJar'],cwd=ROOT,check=True,stdout=sys.stderr,stdin=subprocess.DEVNULL)
        if source!=build_sources():raise RuntimeError('Build sources changed during compilation')
        jar=ROOT/'build/libs/interlis-mcp.jar';jarhash=sha(jar)
        build={'schemaVersion':1,'jarSha256':jarhash,'buildId':digest(source),'sourceFiles':source}
        dest=cache/build['buildId']/jarhash;dest.parent.mkdir(parents=True,exist_ok=True)
        if not dest.exists():
            temp=pathlib.Path(tempfile.mkdtemp(prefix='.build-',dir=cache))
            shutil.copyfile(jar,temp/'interlis-mcp.jar');write(temp/'build.json',build)
            os.chmod(temp/'interlis-mcp.jar',0o444);os.chmod(temp/'build.json',0o444)
            temp.rename(dest)
        elif read(dest/'build.json')!=build or sha(dest/'interlis-mcp.jar')!=jarhash:
            raise RuntimeError('Immutable artifact identity collision')
    # Pin the dependency bytes too: v2 can still be under review in the workspace.
    dephash=sha(manifest);depdir=cache/('dependencies-'+dephash)
    with (cache/'build.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        if not depdir.exists():
            temp=pathlib.Path(tempfile.mkdtemp(prefix='.deps-',dir=cache))
            for p in manifest.parent.iterdir():
                if p.is_file():shutil.copyfile(p,temp/p.name);os.chmod(temp/p.name,0o444)
            temp.rename(depdir)
    verify_dependencies(depdir/'manifest.json')
    actual=(dest/'interlis-mcp.jar').resolve()
    env=os.environ.copy();env.update(INTERLIS_MCP_IMMUTABLE_JAR=str(actual),
       INTERLIS_MCP_DEPENDENCY_MANIFEST=str(depdir/'manifest.json'),INTERLIS_MCP_MODEL_REPOSITORIES=str(depdir))
    java=str(pathlib.Path(env.get('JAVA_HOME','/Users/stefan/.sdkman/candidates/java/current'))/'bin/java')
    if sys.argv[1:]==['--prepare-only']:
        print(json.dumps({'command':[java,'-jar',str(actual)],'environment':{k:env[k] for k in ['INTERLIS_MCP_IMMUTABLE_JAR','INTERLIS_MCP_DEPENDENCY_MANIFEST','INTERLIS_MCP_MODEL_REPOSITORIES']},'build':build}))
        return
    if sys.argv[1:]:raise RuntimeError('Only --prepare-only is supported')
    os.chdir(ROOT)
    os.execve(java,[java,'-jar',str(actual)],env)
if __name__=='__main__':main()
