/*
 * Copyright 2022 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.apidocumentation.controllers

import javax.inject.{Inject, Singleton}
import play.api.mvc._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future.successful
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController
import uk.gov.hmrc.apidocumentation.views.html._
import uk.gov.hmrc.apidocumentation.connectors.DownloadConnector
import uk.gov.hmrc.apidocumentation.util.ApplicationLogger
import uk.gov.hmrc.apidocumentation.services.NavigationService
import uk.gov.hmrc.apidocumentation.models._
import uk.gov.hmrc.apidocumentation.config.ApplicationConfig
import uk.gov.hmrc.apidocumentation.ErrorHandler
import uk.gov.hmrc.apidocumentation.services.LoggedInUserService
import uk.gov.hmrc.apidocumentation.services.ApiDefinitionService
import uk.gov.hmrc.apidocumentation.views.html.openapispec.ParentPageOuter
import scala.concurrent.Future
import uk.gov.hmrc.http.NotFoundException
import play.mvc.Http.HeaderNames

@Singleton
class OpenApiDocumentationController @Inject()(
  openApiViewRedoc: OpenApiViewRedoc,
  openApiPreviewRedoc: OpenApiPreviewRedoc,
  openApiPreviewView: OpenApiPreviewView,
  parentPage: ParentPageOuter,
  retiredVersionJumpView: RetiredVersionJumpView,
  downloadConnector:DownloadConnector,
  mcc: MessagesControllerComponents,
  apiDefinitionService: ApiDefinitionService,
  loggedInUserService: LoggedInUserService,
  errorHandler: ErrorHandler,
  val navigationService: NavigationService
)(implicit val ec: ExecutionContext, appConfig: ApplicationConfig)
    extends FrontendController(mcc) with HeaderNavigation with HomeCrumb with ApplicationLogger {

  private val buildPageAttributes = (navLinks: Seq[NavLink]) => PageAttributes(
    title = "OpenAPI Documentation Preview",
    breadcrumbs = Breadcrumbs(
      Crumb("Preview OpenAPI", routes.OpenApiDocumentationController.previewApiDocumentationPage.url),
      homeCrumb
    ),
    headerLinks = navLinks,
    sidebarLinks = navigationService.sidebarNavigation()
  )

  private def doRenderApiDocumentation(service: String, version: String, apiOption: Option[ExtendedAPIDefinition])(implicit request: Request[AnyContent]): Future[Result] = {
    def renderDocumentationPage(): Future[Result] = {
        successful(Ok(openApiViewRedoc(service, version)))
    }

    def renderNotFoundPage = Future.successful(NotFound(errorHandler.notFoundTemplate))
    def badRequestPage = Future.successful(BadRequest(errorHandler.badRequestTemplate))

    def findVersion(apiOption: Option[ExtendedAPIDefinition]) =
      for {
        api <- apiOption
        apiVersion <- api.versions.find(v => v.version == version)
        visibility <- apiVersion.visibility
      } yield (api, apiVersion, visibility)

    findVersion(apiOption) match {
      case Some((api, selectedVersion, VersionVisibility(_, _, true, _))) if selectedVersion.status == APIStatus.RETIRED  => badRequestPage
      case Some((api, selectedVersion, VersionVisibility(_, _, true, _)))                                                 => renderDocumentationPage()
      case Some((api, selectedVersion, VersionVisibility(APIAccessType.PRIVATE, _, _, Some(true))))                       => renderDocumentationPage()
      case Some((_, _, VersionVisibility(APIAccessType.PRIVATE, false, _, _)))                                            => badRequestPage
      case _                                                                                                              => renderNotFoundPage
    }
    
  }

  private def extractDeveloperIdentifier(f: Future[Option[Developer]]): Future[Option[DeveloperIdentifier]] = {
    f.map( o =>
      o.map(d => UuidIdentifier(d.userId))
    )
  }

  def renderApiDocumentation(service: String, version: String) = 
    headerNavigation { implicit request =>
      navLinks =>
        (for {
          userId <- extractDeveloperIdentifier(loggedInUserService.fetchLoggedInUser())
          api <- apiDefinitionService.fetchExtendedDefinition(service, userId)
          apiDocumentation <- doRenderApiDocumentation(service, version, api)
        } yield apiDocumentation
        ) recover {
          case e: NotFoundException =>
            logger.info(s"Upstream request not found: ${e.getMessage}")
            NotFound(errorHandler.notFoundTemplate)
          case e: Throwable =>
            logger.error("Could not load API Documentation service", e)
            InternalServerError(errorHandler.internalServerErrorTemplate)
        }
    }

  def fetchOas(service: String, version: String) = Action.async { implicit request =>
    downloadConnector.fetch(service, version, "application.yaml")
      .map {
        case Some(result)   => result.withHeaders(HeaderNames.CONTENT_DISPOSITION -> "attachment; filename=\"application.yaml\"")
        case None           => NotFound(errorHandler.notFoundTemplate)
      }
  }
  
  def previewApiDocumentationPage(): Action[AnyContent] = headerNavigation { implicit request =>
    navLinks =>
      if (appConfig.openApiPreviewEnabled) {
        val pageAttributes = buildPageAttributes(navLinks)

        successful(Ok(openApiPreviewView(pageAttributes)))
      } else {
        successful(NotFound(errorHandler.notFoundTemplate))
      }
  }

  def previewApiDocumentationAction(url: Option[String]) = headerNavigation { implicit request =>
    navLinks =>
      if (appConfig.openApiPreviewEnabled) {
        val pageAttributes = buildPageAttributes(navLinks)

        url match {
          case None           => successful(Ok(openApiPreviewView(pageAttributes)))
          case Some(location) => successful(Ok(openApiPreviewRedoc(location)))
        }
      } else {
        successful(NotFound(errorHandler.notFoundTemplate))
      }
  }
}
