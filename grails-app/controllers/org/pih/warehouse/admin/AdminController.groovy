/**
 * Copyright (c) 2012 Partners In Health.  All rights reserved.
 * The use and distribution terms for this software are covered by the
 * Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 * which can be found in the file epl-v10.html at the root of this distribution.
 * By using this software in any fashion, you are agreeing to be bound by
 * the terms of this license.
 * You must not remove this notice, or any other, from this software.
 **/
package org.pih.warehouse.admin

import grails.core.GrailsApplication
import grails.util.Holders
import grails.validation.Validateable
import org.pih.warehouse.core.MailService
import org.pih.warehouse.jobs.SendStockAlertsJob
import org.springframework.boot.info.GitProperties
import org.springframework.web.multipart.MultipartFile

import javax.print.*
import java.awt.print.PrinterJob
import java.util.concurrent.FutureTask

class AdminController {

    def sessionFactory // inject Hibernate sessionFactory
    MailService mailService
    GrailsApplication grailsApplication
    def config = Holders.getConfig()
    def quartzScheduler
    def dataService
    GitProperties gitProperties

    def index() {}

    def controllerActions() {

        List actionNames = []
        grailsApplication.controllerClasses.sort { it.logicalPropertyName }.each { controller ->

            controller.reference.propertyDescriptors.each { pd ->
                def closure = controller.getPropertyOrStaticPropertyOrFieldValue(pd.name, Closure)
                if (closure) {
                    if (pd.name != 'beforeInterceptor' && pd.name != 'afterInterceptor') {
                        actionNames << controller.logicalPropertyName + "." + pd.name + ".label = " + pd.name
                    }
                }
            }
            println "$controller.clazz.simpleName: $actionNames"
        }

        [actionNames: actionNames]
    }

    def triggerStockAlerts = {
        SendStockAlertsJob.triggerNow([:])
        flash.message = "Triggered send stock alerts job in background"
        redirect(controller: "admin", action: "showSettings")
    }

    def cache() {
        [cacheStatistics: sessionFactory.getStatistics()]
    }

    def plugins() {}
    def status() {}

    def static LOCAL_TEMP_WEBARCHIVE_PATH = "warehouse.war"

    def showUpgrade(UpgradeCommand command) {
        log.info "show upgrade " + params

        [
                command: session.command
        ]
    }

    def evictDomainCache() {
        def domainClass = grailsApplication.getDomainClass(params.name)
        if (domainClass) {
            sessionFactory.evict(domainClass.clazz)
            flash.message = "Domain cache '${params.name}' was invalidated"
        } else {
            flash.message = "Domain cache '${params.name}' does not exist"
        }
        redirect(action: "showSettings")
    }

    def evictQueryCache() {
        if (params.name) {
            sessionFactory.evictQueries(params.name)
            flash.message = "Query cache '${params.name}' was invalidated"
        } else {
            sessionFactory.evictQueries()
            flash.message = "All query caches were invalidated"
        }
        redirect(action: "showSettings")
    }


    def sendMail() {
        if (request.method == "POST") {
            try {
                withForm {
                    MultipartFile multipartFile = request.getFile('file')
                    if (!multipartFile.empty) {
                        def success = mailService.sendHtmlMailWithAttachment(
                                session?.user,
                                params.list("to"),
                                null,
                                params["subject"],
                                params["message"],
                                multipartFile?.bytes,
                                multipartFile?.originalFilename,
                                multipartFile?.contentType
                        )

                        if (success) {
                            flash.message = "Multipart email with subject ${params.subject} and attachment ${multipartFile.originalFilename} has been sent to ${params.to}"
                        } else {
                            flash.message = "Could not send email with subject ${params.subject} and attachment ${multipartFile.originalFilename} to ${params.to}"
                        }
                    } else {
                        if (params.includesHtml) {
                            mailService.sendHtmlMail(params.subject, params.message, params.to)
                            flash.message = "HTML email with subject ${params.subject} has been sent to ${params.to}"
                        } else {
                            mailService.sendMail(params.subject, params.message, params.to)
                            flash.message = "Text email with subject ${params.subject} has been sent to ${params.to}"
                        }
                    }
                }.invalidToken {
                    flash.message = "Invalid token"
                }
            } catch (Exception e) {
                flash.message = "Unable to send email due to error: " + e.message
            }
        }
        render(view: "sendMail")
    }


    def download(UpgradeCommand command) {
        log.info "download " + params
        if (command?.remoteWebArchiveUrl) {
            session.command = command
            session.command.future = null
            session.command?.localWebArchive = new File("warehouse.war")
            flash.message = "Attempting to download '" + command?.remoteWebArchiveUrl + "' to '" + command?.localWebArchive?.absolutePath + "'"
        } else {
            flash.message = "Please enter valid web archive url"

        }

        chain(action: "showUpgrade", model: [command: command])
    }


    def deploy(UpgradeCommand command) {
        log.info "deploy " + params

        session.command.localWebArchivePath = command.localWebArchivePath
        command.localWebArchive = session.command.localWebArchive

        def source = session.command.localWebArchive
        def destination = new File(session.command.localWebArchivePath)
        def backup = new File(session.command.localWebArchive.absolutePath + ".backup")
        log.info "Copying wbe archive to backup " + source.absolutePath + " to " + backup.absolutePath
        backup.bytes = source.bytes

        log.info "Copying web archive to web container " + destination.absolutePath
        destination.bytes = source.bytes

        chain(action: "showUpgrade", model: [command: command])
    }

    def showDatabaseStatus() {
        def results = dataService.executeQuery("show engine innodb status")
        render "<pre>${results.Status[0]}</pre>"
    }

    def showDatabaseProcessList() {
        def processlist = dataService.executeQuery("show processlist")

        render "<pre>${processlist.join('<br/>')}</pre>"
    }

    def showSettings() {

        PrintService[] printServices = PrinterJob.lookupPrintServices()

//        def caches = new ArrayList()
//        def cacheNames = springcacheService.springcacheCacheManager.cacheNames
//
//        for (cacheName in cacheNames) {
//            Cache cache = springcacheService.springcacheCacheManager.getCache(cacheName)
//            if (cache instanceof Cache) {
//                caches.add(cache)
//            }
//        }


        [
                gitProperties           : gitProperties,
                quartzScheduler         : quartzScheduler,
                printServices           : printServices,
                caches                  : null, //caches,
                enabled                 : Boolean.valueOf(grailsApplication.config.grails.mail.enabled),
                from                    : "${config.getProperty("grails.mail.from")}",
                host                    : "${config.getProperty("grails.mail.host")}",
                port                    : "${config.getProperty("grails.mail.port")}"
        ]
    }


    def downloadWar() {
        log.info("Updating war file " + params)
        redirect(action: "showSettings")
    }

    def cancelUpdateWar() {
        if (session.future) {
            session.future.cancel(true)
            new File(LOCAL_TEMP_WEBARCHIVE_PATH).delete()
        }
        redirect(action: "showSettings")
    }

    def deployWar(UpgradeCommand) {
        def source = session.command.localWebArchive

        def backup = new File(session.command.localWebArchive.absolutePath + ".backup")
        log.info "Backing up " + source.absolutePath + " to " + backup.absolutePath
        backup.bytes = source.bytes

        redirect(action: "showSettings")
    }
}

class UpgradeCommand implements Validateable {

    FutureTask future
    File localWebArchive
    String remoteWebArchiveUrl
    String localWebArchivePath

    static constraints() {
        future(nullable: true)
        localWebArchive(nullable: true)
        remoteWebArchiveUrl(nullable: true)
        localWebArchivePath(nullable: true)
    }


    Integer getRemoteFileSize() {
        if (remoteWebArchiveUrl) {
            HttpURLConnection conn = null
            try {
                conn = (HttpURLConnection) new URL(remoteWebArchiveUrl).openConnection()
                conn.setRequestMethod("HEAD")
                conn.getInputStream()
                return conn.getContentLength()
            } catch (IOException e) {
                return -1
            } finally {
                if (conn) conn.disconnect()
            }
        }
        return -1
    }
}
