/*******************************************************************************
 * Copyright (c) 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 ******************************************************************************/

package org.eclipse.ui.internal.provisional.views.markers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.ui.IWorkingSet;
import org.eclipse.ui.statushandlers.StatusManager;
import org.eclipse.ui.views.markers.internal.FieldMarkerGroup;
import org.eclipse.ui.views.markers.internal.MarkerFilter;
import org.eclipse.ui.views.markers.internal.MarkerMessages;
import org.eclipse.ui.views.markers.internal.MarkerSupportRegistry;
import org.eclipse.ui.views.markers.internal.MarkerType;
import org.eclipse.ui.views.markers.internal.MarkerTypesModel;

/**
 * MarkerContentGenerator is the representation of the markerContentGenerator
 * extension point.
 * 
 * @since 3.4
 * 
 */
public class MarkerContentGenerator {

	private static final String ATTRIBUTE_DEFAULT_FOR_PERSPECTIVE = "defaultForPerspective"; //$NON-NLS-1$
	private static final String ATTRIBUTE_DEFAULT_MARKER_GROUPING = "defaultMarkerGrouping"; //$NON-NLS-1$
	private static final String ATTRIBUTE_ID = "id"; //$NON-NLS-1$
	private static final String ATTRIBUTE_VISIBLE = "visible"; //$NON-NLS-1$
	static final Object CACHE_UPDATE_FAMILY = new Object();
	private static final IResource[] EMPTY_RESOURCE_ARRAY = new IResource[0];
	private static final String MARKER_FIELD_REFERENCE = "markerFieldReference"; //$NON-NLS-1$
	private static final Object VALUE_FALSE = "false"; //$NON-NLS-1$
	private MarkerField categoryField;
	private IConfigurationElement configurationElement;
	private Collection markerTypes;
	private MarkerField[] sortFields;
	private MarkerField[] visibleFields;
	private IWorkingSet workingSet;
	private String name;

	/**
	 * Create a new MarkerContentGenerator
	 * 
	 * @param id
	 * @param name
	 */
	public MarkerContentGenerator(String id, String name) {
		this.name = name;

	}

	/**
	 * Compute all of the markers for the receiver's type.
	 * 
	 * @param subMonitor
	 * @return MarkerEntry
	 */
	private Collection computeAllMarkers(SubProgressMonitor subMonitor) {
		Collection allMarkers = new HashSet();
		findMarkers(allMarkers, new IResource[] { ResourcesPlugin
				.getWorkspace().getRoot() }, IResource.DEPTH_INFINITE,
				subMonitor);
		return allMarkers;
	}

	/**
	 * Compute the marker for the supplied filter and add to return markers.
	 * 
	 * @param returnMarkers
	 * @param subMonitor
	 * @param markerFilter
	 */
	private void computeMarkers(Collection returnMarkers,
			SubProgressMonitor subMonitor, MarkerFilter markerFilter) {

		int filterType = markerFilter.getOnResource();

		switch (filterType) {
		case MarkerFilter.ON_ANY: {
			findMarkers(returnMarkers, new IResource[] { ResourcesPlugin
					.getWorkspace().getRoot() }, IResource.DEPTH_INFINITE,
					subMonitor);
			break;
		}
		case MarkerFilter.ON_SELECTED_ONLY: {
			findMarkers(returnMarkers, getFocusResources(),
					IResource.DEPTH_ZERO, subMonitor);
			break;
		}
		case MarkerFilter.ON_SELECTED_AND_CHILDREN: {
			findMarkers(returnMarkers, getFocusResources(),
					IResource.DEPTH_INFINITE, subMonitor);
			break;
		}
		case MarkerFilter.ON_ANY_IN_SAME_CONTAINER: {
			findMarkers(returnMarkers, getProjects(getFocusResources()),
					IResource.DEPTH_INFINITE, subMonitor);
			break;
		}
		case MarkerFilter.ON_WORKING_SET: {
			findMarkers(returnMarkers, getResourcesInWorkingSet(),
					IResource.DEPTH_INFINITE, subMonitor);
		}
		}

	}

	/**
	 * Adds all markers in the given set of resources to the given list
	 * 
	 * @param results
	 *            The Collection to add new entries to
	 * @param resources
	 * @param markerTypeId
	 * @param depth
	 */
	private void findMarkers(Collection results, IResource[] resources,
			int depth, IProgressMonitor monitor) {
		if (resources == null) {
			return;
		}

		// Optimisation: if a type appears in the selectedTypes list along with
		// all of its sub-types, then combine these in a single search.

		Collection selectedTypes = getMarkerTypes();

		// List of types that haven't been replaced by one of their super-types
		HashSet typesToSearch = new HashSet(selectedTypes.size());

		// List of types that appeared in selectedTypes along with all of their
		// sub-types
		HashSet includeAllSubtypes = new HashSet(selectedTypes.size());

		typesToSearch.addAll(selectedTypes);

		Iterator iter = selectedTypes.iterator();

		while (iter.hasNext()) {
			MarkerType type = (MarkerType) iter.next();

			Collection subtypes = Arrays.asList(type.getAllSubTypes());

			if (selectedTypes.containsAll(subtypes)) {
				typesToSearch.removeAll(subtypes);

				includeAllSubtypes.add(type);
			}
		}

		monitor.beginTask(MarkerMessages.MarkerFilter_searching, typesToSearch
				.size()
				* resources.length);

		// Use this hash set to determine if there are any resources in the
		// list that appear along with their parent.
		HashSet resourcesToSearch = new HashSet();

		// Insert all the resources into the Set
		for (int idx = 0; idx < resources.length; idx++) {
			IResource next = resources[idx];

			if (!next.exists())
				continue;

			if (resourcesToSearch.contains(next))
				monitor.worked(typesToSearch.size());
			else
				resourcesToSearch.add(next);
		}

		// Iterate through all the selected resources
		for (int resourceIdx = 0; resourceIdx < resources.length; resourceIdx++) {
			iter = typesToSearch.iterator();

			IResource resource = resources[resourceIdx];

			// Skip resources that don't exist
			if (!resource.isAccessible()) {
				continue;
			}

			if (depth == IResource.DEPTH_INFINITE) {
				// Determine if any parent of this resource is also in our
				// filter
				IResource parent = resource.getParent();
				boolean found = false;
				while (parent != null) {
					if (resourcesToSearch.contains(parent)) {
						found = true;
					}

					parent = parent.getParent();
				}

				// If a parent of this resource is also in the filter, we can
				// skip it
				// because we'll pick up its markers when we search the parent.
				if (found) {
					continue;
				}
			}

			// Iterate through all the marker types
			while (iter.hasNext()) {
				MarkerType markerType = (MarkerType) iter.next();
				try {
					// Only search for subtypes of the marker if we found all of
					// its subtypes in the filter criteria.
					IMarker[] markers = resource.findMarkers(
							markerType.getId(), includeAllSubtypes
									.contains(markerType), depth);

					monitor.worked(1);

					for (int idx = 0; idx < markers.length; idx++) {
						MarkerItem marker;

						marker = new MarkerEntry(markers[idx]);

						results.add(marker);
					}
				} catch (CoreException e) {
					StatusManager.getManager().handle(e.getStatus());
				}
			}
		}

		monitor.done();
	}

	/**
	 * Re-generate all of the markers and filter them based on the enabled
	 * filters.
	 * 
	 * @param subMonitor
	 * @return MarkerMap
	 */
	public MarkerMap generateFilteredMarkers(SubProgressMonitor subMonitor) {

		MarkerFilter[] filters = getEnabledFilters();
		Collection returnMarkers;
		if (filters.length > 0) {
			returnMarkers = new HashSet();
			for (int i = 0; i < filters.length; i++) {
				computeMarkers(returnMarkers, subMonitor, filters[i]);
			}

		} else
			returnMarkers = computeAllMarkers(subMonitor);
		MarkerEntry[] entries = new MarkerEntry[returnMarkers.size()];
		returnMarkers.toArray(entries);
		return new MarkerMap(entries);
	}

	/**
	 * Return the field used to generate categories.
	 * 
	 * @return IMarkerField for <code>null</code>.
	 */
	public MarkerField getCategoryField() {

		return categoryField;
	}

	/**
	 * Return a new instance of the receiver with the fiels
	 * 
	 * @return MarkerComparator
	 */
	public MarkerComparator getComparator() {
		return new MarkerComparator(getCategoryField(), getSortingFields());
	}

	/**
	 * Get the id of the perspective this content generator is the default for.
	 * 
	 * @return String or <code>null</code>.
	 */
	public String getDefaultPerspectiveId() {
		return configurationElement
				.getAttribute(ATTRIBUTE_DEFAULT_FOR_PERSPECTIVE);
	}

	/**
	 * Return the enabled filters for the receiver.
	 * 
	 * @return
	 */
	public MarkerFilter[] getEnabledFilters() {
		// TODO Get some real content here
		return new MarkerFilter[0];
	}

	/**
	 * Return the current focussed resources.
	 * 
	 * @return
	 */
	private IResource[] getFocusResources() {
		// TODO tie this to the selection
		return new IResource[0];
	}

	/**
	 * Return the markerTypes for the receiver.
	 * 
	 * @return Collection of {@link MarkerType}
	 */
	public Collection getMarkerTypes() {
		if (markerTypes == null) {
			markerTypes = new HashSet();
			IConfigurationElement[] markerTypeElements = configurationElement
					.getChildren(MarkerSupportRegistry.MARKER_TYPE_REFERENCE);
			for (int i = 0; i < markerTypeElements.length; i++) {
				IConfigurationElement configurationElement = markerTypeElements[i];
				String elementName = configurationElement
						.getAttribute(ATTRIBUTE_ID);
				MarkerType[] types = MarkerTypesModel.getInstance().getType(
						elementName).getAllSubTypes();
				for (int j = 0; j < types.length; j++) {
					markerTypes.add(types[j]);
				}
				markerTypes.add(MarkerTypesModel.getInstance().getType(
						elementName));
			}
			if (markerTypes.isEmpty()) {
				MarkerType[] types = MarkerTypesModel.getInstance().getType(
						IMarker.PROBLEM).getAllSubTypes();
				for (int i = 0; i < types.length; i++) {
					markerTypes.add(types[i]);
				}
			}
		}
		return markerTypes;
	}

	/**
	 * @param focusResources
	 * @return
	 */
	private IResource[] getProjects(IResource[] focusResources) {

		if (focusResources.length == 0)
			return EMPTY_RESOURCE_ARRAY;
		HashSet projects = new HashSet();

		for (int idx = 0; idx < focusResources.length; idx++) {
			projects.add(focusResources[idx].getProject());
		}
		if (projects.isEmpty())
			return EMPTY_RESOURCE_ARRAY;
		return (IResource[]) projects.toArray(new IResource[projects.size()]);

	}

	/**
	 * Get the resources in the current working set.
	 * 
	 * @return IResource[]
	 */
	private IResource[] getResourcesInWorkingSet() {

		// TODO hook up working sets
		if (workingSet == null) {
			return new IResource[0];
		}

		if (workingSet.isEmpty()) {
			return new IResource[] { ResourcesPlugin.getWorkspace().getRoot() };
		}

		IAdaptable[] elements = workingSet.getElements();
		List result = new ArrayList(elements.length);

		for (int idx = 0; idx < elements.length; idx++) {
			IResource next = (IResource) elements[idx]
					.getAdapter(IResource.class);

			if (next != null) {
				result.add(next);
			}
		}

		return (IResource[]) result.toArray(new IResource[result.size()]);

	}

	/**
	 * Get the fields that this content generator is using to sort
	 * 
	 * @return {@link MarkerField}[]
	 */
	public MarkerField[] getSortingFields() {
		return sortFields;
	}

	/**
	 * Get the fields that this content generator is displaying
	 * 
	 * @return {@link MarkerField}[]
	 */
	public MarkerField[] getVisibleFields() {
		return visibleFields;
	}

	/**
	 * Initialise the receiver from the configuration element. This is done as a
	 * post processing step.
	 * 
	 * @param registry
	 *            the MarkerSupportRegistry being used to initialise the
	 *            receiver.
	 */
	public void initializeFromConfigurationElement(
			MarkerSupportRegistry registry) {
		String categoryName = configurationElement
				.getAttribute(ATTRIBUTE_DEFAULT_MARKER_GROUPING);
		if (categoryName != null) {
			FieldMarkerGroup group = registry.getMarkerGroup(categoryName);
			if (group != null)
				categoryField = new MarkerGroupField(group);
		}

		IConfigurationElement[] elements = configurationElement
				.getChildren(MARKER_FIELD_REFERENCE);
		Collection sortFieldList = new ArrayList();
		Collection visibleFieldList = new ArrayList();
		for (int i = 0; i < elements.length; i++) {
			MarkerField field = registry.getField(elements[i]
					.getAttribute(ATTRIBUTE_ID));
			if (field == null)
				continue;
			sortFieldList.add(field);
			if (!VALUE_FALSE
					.equals(elements[i].getAttribute(ATTRIBUTE_VISIBLE)))
				visibleFieldList.add(field);
		}

		sortFields = new MarkerField[sortFieldList.size()];
		sortFieldList.toArray(sortFields);

		visibleFields = new MarkerField[visibleFieldList.size()];
		visibleFieldList.toArray(visibleFields);

	}

	/**
	 * Return whether or not we are showing a hierarchy,.
	 * 
	 * @return <code>true</code> if a hierarchy is being shown.
	 */
	public boolean isShowingHierarchy() {
		return categoryField != null;
	}

	/**
	 * Set the configuration element used to define the receiver.
	 * 
	 * @param element
	 */
	public void setConfigurationElement(IConfigurationElement element) {
		configurationElement = element;
	}

	/**
	 * Return the name for the receiver.
	 * @return String
	 */
	public String getName() {
		return name;
	}

}
