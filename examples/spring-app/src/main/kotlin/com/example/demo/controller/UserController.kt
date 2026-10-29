package com.example.demo.controller

import com.example.demo.domain.User
import io.github.octaviusframework.driver.spring.OctaviusTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import kotlin.uuid.Uuid

@RestController
class UserController(private val octaviusTemplate: OctaviusTemplate) {


    @PostMapping("/users")
    @Transactional
    fun createUser(@RequestBody user: User): User {
        return octaviusTemplate.execute {
            createNamedQuery(
                """
                INSERT INTO users (name, role, primary_address, shipping_addresses, profile)
                VALUES (@name, @role, @primary_address, @shipping_addresses, @profile)
                RETURNING *
            """
            ).fetchObjectStrict<User>(
                "name" to user.name,
                "role" to user.role,
                "primary_address" to user.primaryAddress,
                "shipping_addresses" to user.shippingAddresses,
                "profile" to user.profile
            )
        }
    }

    @GetMapping("/users")
    @Transactional(readOnly = true)
    fun getUsers(): List<User> {
        return octaviusTemplate.execute {
            createNamedQuery("SELECT * FROM users")
                .fetchObjects<User>()
        }
    }

    @GetMapping("/users/{id}")
    fun getUser(@PathVariable id: Uuid): User {
        return octaviusTemplate.execute {
            val row = createNamedQuery("SELECT * FROM users WHERE id = @id")
                .fetchRowStrict("id" to id)

            User(
                id = row.get("id"),
                name = row.get("name"),
                role = row.get("role"),
                primaryAddress = row.get("primary_address"),
                shippingAddresses = row.get("shipping_addresses"),
                profile = row.get("profile")
            )
        }
    }

    @PostMapping("/users/demo-rollback")
    @Transactional
    fun demoRollback(@RequestBody user: User): User {
        octaviusTemplate.execute {
            createNamedQuery(
                """
                INSERT INTO users (name, role, primary_address, shipping_addresses, profile)
                VALUES (@name, @role, @primary_address, @shipping_addresses, @profile)
            """
            ).executeUpdate(
                "name" to user.name,
                "role" to user.role,
                "primary_address" to user.primaryAddress,
                "shipping_addresses" to user.shippingAddresses,
                "profile" to user.profile
            )
        }

        throw RuntimeException("Intentional exception to trigger transaction rollback!")
    }

    @PostMapping("/users/demo-readonly")
    @Transactional(readOnly = true)
    fun demoReadOnly(@RequestBody user: User): User {
        return octaviusTemplate.execute {
            createNamedQuery(
                """
                INSERT INTO users (name, role, primary_address, shipping_addresses, profile)
                VALUES (@name, @role, @primary_address, @shipping_addresses, @profile)
                RETURNING *
            """
            ).fetchRowStrict(
                "name" to user.name,
                "role" to user.role,
                "primary_address" to user.primaryAddress,
                "shipping_addresses" to user.shippingAddresses,
                "profile" to user.profile
            )

            user
        }
    }
}
