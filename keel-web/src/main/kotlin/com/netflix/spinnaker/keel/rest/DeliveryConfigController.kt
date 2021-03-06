package com.netflix.spinnaker.keel.rest

import com.netflix.spinnaker.keel.api.DeliveryConfig
import com.netflix.spinnaker.keel.constraints.ConstraintState
import com.netflix.spinnaker.keel.constraints.UpdatedConstraintStatus
import com.netflix.spinnaker.keel.core.api.SubmittedDeliveryConfig
import com.netflix.spinnaker.keel.diff.AdHocDiffer
import com.netflix.spinnaker.keel.diff.EnvironmentDiff
import com.netflix.spinnaker.keel.exceptions.InvalidConstraintException
import com.netflix.spinnaker.keel.persistence.KeelRepository
import com.netflix.spinnaker.keel.persistence.ResourceRepository.Companion.DEFAULT_MAX_EVENTS
import com.netflix.spinnaker.keel.yaml.APPLICATION_YAML_VALUE
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.parameters.RequestBody as SwaggerRequestBody
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(path = ["/delivery-configs"])
class DeliveryConfigController(
  private val repository: KeelRepository,
  private val adHocDiffer: AdHocDiffer
) {
  @Operation(
    description = "Registers or updates a delivery config manifest."
  )
  @PostMapping(
    consumes = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE],
    produces = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE]
  )
  fun upsert(
    @RequestBody
    @SwaggerRequestBody(
      description = "The delivery config. If its `name` matches an existing delivery config the operation is an update, otherwise a new delivery config is created."
    )
    deliveryConfig: SubmittedDeliveryConfig
  ): DeliveryConfig =
    repository.upsertDeliveryConfig(deliveryConfig)

  @GetMapping(
    path = ["/{name}"],
    produces = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE]
  )
  fun get(@PathVariable("name") name: String): DeliveryConfig =
    repository.getDeliveryConfig(name)

  @DeleteMapping(
    path = ["/{name}"],
    produces = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE]
  )
  fun delete(@PathVariable("name") name: String): DeliveryConfig {
    val deliveryConfig = repository.getDeliveryConfig(name)
    log.info("Deleting delivery config $name: $deliveryConfig")
    repository.deleteDeliveryConfig(name)
    return deliveryConfig
  }

  // todo eb: make this work with artifact references
  @PostMapping(
    path = ["/diff"],
    consumes = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE],
    produces = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE]
  )
  fun diff(@RequestBody deliveryConfig: SubmittedDeliveryConfig): List<EnvironmentDiff> =
    adHocDiffer.calculate(deliveryConfig)

  @PostMapping(
    path = ["/validate"],
    consumes = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE],
    produces = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE]
  )
  fun validate(@RequestBody deliveryConfig: SubmittedDeliveryConfig) =
  // TODO: replace with JSON schema/OpenAPI spec validation when ready (for now, leveraging parsing error handling
    //  in [ExceptionHandler])
    mapOf("status" to "valid")

  @GetMapping(
    path = ["/{name}/environment/{environment}/constraints"],
    produces = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE]
  )
  fun getConstraintState(
    @PathVariable("name") name: String,
    @PathVariable("environment") environment: String,
    @RequestParam("limit") limit: Int?
  ): List<ConstraintState> =
    repository.constraintStateFor(name, environment, limit ?: DEFAULT_MAX_EVENTS)

  @PostMapping(
    path = ["/{name}/environment/{environment}/constraint"],
    consumes = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE],
    produces = [APPLICATION_JSON_VALUE, APPLICATION_YAML_VALUE]
  )
  // TODO: This should be validated against write access to a service account. Service accounts should
  //  become a top-level property of either delivery configs or environments.
  fun updateConstraintStatus(
    @RequestHeader("X-SPINNAKER-USER") user: String,
    @PathVariable("name") name: String,
    @PathVariable("environment") environment: String,
    @RequestBody status: UpdatedConstraintStatus
  ) {
    val currentState = repository.getConstraintState(
      name,
      environment,
      status.artifactVersion,
      status.type) ?: throw InvalidConstraintException(
      "$name/$environment/${status.type}/${status.artifactVersion}", "constraint not found")

    repository.storeConstraintState(
      currentState.copy(
        status = status.status,
        comment = status.comment ?: currentState.comment,
        judgedAt = Instant.now(),
        judgedBy = user))
  }

  private val log by lazy { LoggerFactory.getLogger(javaClass) }
}
