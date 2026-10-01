/**
 * Copyright (c) 2026 João Dinis Ferreira and others.
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.xtext.ui.tests.core.resource;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.xtext.ui.resource.Storage2UriMapperJavaImpl;
import org.eclipse.xtext.ui.resource.Storage2UriMapperJavaImpl.PackageFragmentRootData;
import org.junit.Assert;
import org.junit.Test;

/**
 * Tests that the cache of {@link Storage2UriMapperJavaImpl} stays consistent when another thread updates it at the same
 * time. The other thread's update is run deterministically from a hook at the point where it interferes.
 */
public class Storage2UriMapperJavaImplConcurrencyTest extends Assert {

	private static final String A_JAR = "/lib/a.jar";

	@Test
	public void testRootCachedConcurrentlyIsShared() {
		TestableMapper mapper = new TestableMapper();
		IPackageFragmentRoot pRoot = root(project("p"), A_JAR);
		IPackageFragmentRoot qRoot = root(project("q"), A_JAR);
		// another thread caches the same jar for q while this one is initializing it for p
		mapper.onInitializeData = () -> mapper.cachedData(qRoot);
		PackageFragmentRootData data = mapper.cachedData(pRoot);
		assertSame(data, mapper.cache().get(A_JAR));
		assertEquals(Set.of(pRoot.getHandleIdentifier(), qRoot.getHandleIdentifier()), data.associatedRoots.keySet());
	}

	@Test
	public void testOutdatedRootCachedConcurrentlyIsReplaced() {
		TestableMapper mapper = new TestableMapper();
		mapper.upToDate = false;
		IPackageFragmentRoot pRoot = root(project("p"), A_JAR);
		mapper.onInitializeData = () -> mapper.cachedData(root(project("q"), A_JAR));
		PackageFragmentRootData data = mapper.cachedData(pRoot);
		assertSame(data, mapper.cache().get(A_JAR));
		assertEquals(Set.of(pRoot.getHandleIdentifier()), data.associatedRoots.keySet());
	}

	private static class TestableMapper extends Storage2UriMapperJavaImpl {

		private Runnable onInitializeData;

		private boolean upToDate = true;

		@Override
		protected PackageFragmentRootData initializeData(IPackageFragmentRoot root) {
			Runnable hook = onInitializeData;
			onInitializeData = null;
			if (hook != null) {
				hook.run();
			}
			PackageFragmentRootData data = new PackageFragmentRootData(null);
			data.addRoot(root);
			return data;
		}

		@Override
		protected boolean isUpToDate(PackageFragmentRootData data, IPackageFragmentRoot root) {
			return upToDate;
		}

		PackageFragmentRootData cachedData(IPackageFragmentRoot root) {
			return getCachedData(root);
		}

		Map<String, PackageFragmentRootData> cache() {
			return cachedPackageFragmentRootData;
		}
	}

	private static IJavaProject project(String name) {
		return fake(IJavaProject.class, name, (method, args) -> {
			throw new UnsupportedOperationException(method.getName());
		});
	}

	private static IPackageFragmentRoot root(IJavaProject project, String jar) {
		return fake(IPackageFragmentRoot.class, project + jar, (method, args) -> switch (method.getName()) {
			case "getPath" -> new Path(jar);
			case "getHandleIdentifier" -> "=" + project + jar;
			default -> throw new UnsupportedOperationException(method.getName());
		});
	}

	private interface Behaviour {
		Object invoke(Method method, Object[] args);
	}

	private static <T> T fake(Class<T> type, String name, Behaviour behaviour) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> switch (method.getName()) {
			case "equals" -> proxy == args[0];
			case "hashCode" -> System.identityHashCode(proxy);
			case "toString" -> name;
			default -> behaviour.invoke(method, args);
		}));
	}
}
