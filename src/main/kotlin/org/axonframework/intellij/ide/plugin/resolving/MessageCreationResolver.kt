/*
 *  Copyright (c) 2022. Axon Framework
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.axonframework.intellij.ide.plugin.resolving

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.search.searches.MethodReferencesSearch
import com.intellij.psi.util.CachedValue
import org.axonframework.intellij.ide.plugin.api.MessageCreator
import org.axonframework.intellij.ide.plugin.resolving.creators.DefaultMessageCreator
import org.axonframework.intellij.ide.plugin.util.areAssignable
import org.axonframework.intellij.ide.plugin.util.axonScope
import org.axonframework.intellij.ide.plugin.util.createCachedValue
import org.axonframework.intellij.ide.plugin.util.findParentHandlers
import org.axonframework.intellij.ide.plugin.util.handlerResolver
import org.axonframework.intellij.ide.plugin.util.javaFacade
import org.axonframework.intellij.ide.plugin.util.measure
import java.util.concurrent.ConcurrentHashMap

/**
 * Searches the codebase for places where a message payload is constructed.
 * It does this by searching for constructor references of compatible payloads. Inheritance is supported.
 *
 * Results are cached based on the Psi modifications of IntelliJ. This means the calculations are invalidated when
 * the PSI is modified (code is edited) or is collected by the garbage collector.
 */
class MessageCreationResolver(private val project: Project) {
    private val handlerResolver = project.handlerResolver()
    private val psiFacade = project.javaFacade()
    private val constructorsByPayloadCache = ConcurrentHashMap<String, CachedValue<List<MessageCreator>>>()

    /**
     * Retrieves all MessageCreator instances for a given payload. Will cache results, so don't worry about
     * calling it multiple times.
     *
     * @param payload qualified name of the payload
     * @return all message creators for the given payload
     */
    fun getCreatorsForPayload(payload: String): List<MessageCreator> {
        val cache = constructorsByPayloadCache.getOrPut(payload) {
            project.createCachedValue {
                findByPayload(payload)
            }
        }
        return cache.value
    }

    private fun findByPayload(payload: String): List<MessageCreator> {
        val matchingHandlers = handlerResolver.findAllHandlers()
            .map { it.payload }
            .filter { areAssignable(project, payload, it) }
        val classesForQualifiedName = listOf(payload).plus(matchingHandlers)
            .distinct()
        return resolveCreatorsForFqns(classesForQualifiedName)
    }

    private fun findAll(): List<MessageCreator> {
        val handlers = handlerResolver.findAllHandlers()
        val payloads = handlers.map { it.payload }.distinct()
        return payloads.flatMap { findByPayload(it) }
    }

    private fun resolveCreatorsForFqns(fqns: List<String>): List<MessageCreator> {
        return fqns.flatMap { typeFqn ->
            psiFacade.findClasses(typeFqn, project.axonScope()).flatMap { clazz ->
                clazz.constructors
                    .flatMap { MethodReferencesSearch.search(it, project.axonScope(), true) }
                    .flatMap { ref -> createCreators(typeFqn, ref.element) }
                    .distinct()
            }
        }
    }

    /**
     * Finds the already constructed/found creator in the caches. Useful for quick filtering in line marker popups.
     */
    fun findCreatorByElement(element: PsiElement): MessageCreator? {
        return constructorsByPayloadCache.values.filter { it.hasUpToDateValue() }
            .flatMap { it.value }
            .firstOrNull { it.element == element }
    }

    /**
     * This action is VERY expensive. Should only be used if the user does not depend on it or is expected to wait.
     * For example, when creating an Event Modeling board based on this info.
     *
     * @return List of all message creators in an application
     */
    fun resolveAllCreators(): List<MessageCreator> {
        return findAll()
            .flatMap { createCreators(it.payload, it.element) }
    }

    private fun createCreators(payload: String, element: PsiElement): List<MessageCreator> {
        val parentHandlers = project.measure("MessageCreationResolver", "findParentHandlers") {
            element.findParentHandlers()
        }
        if (parentHandlers.isEmpty()) {
            return listOf(DefaultMessageCreator(element, payload, null))
        }
        return parentHandlers.map { DefaultMessageCreator(element, payload, it) }
    }
}
