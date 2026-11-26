package com.example.ts15quarkusbanking.api;

import com.example.ts15quarkusbanking.runtime.AccountService;
import com.example.ts15quarkusbanking.runtime.Contracts;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

/** The features' rules as HTTP: a missing role is 403, a broken field rule 422 with the fields, a taken email 409. */
public final class ContractExceptionMappers {
    private ContractExceptionMappers() {}

    @Provider
    public static class Forbidden implements ExceptionMapper<Contracts.ForbiddenException> {
        @Override
        public Response toResponse(Contracts.ForbiddenException e) {
            return Response.status(403).type(MediaType.APPLICATION_JSON).entity(Map.of("detail", String.valueOf(e.getMessage()))).build();
        }
    }

    @Provider
    public static class InvalidFields implements ExceptionMapper<Contracts.FieldValidationException> {
        @Override
        public Response toResponse(Contracts.FieldValidationException e) {
            return Response.status(422).type(MediaType.APPLICATION_JSON).entity(Map.of("detail", String.valueOf(e.getMessage()), "errors", e.errors())).build();
        }
    }

    @Provider
    public static class Conflict implements ExceptionMapper<AccountService.ConflictException> {
        @Override
        public Response toResponse(AccountService.ConflictException e) {
            return Response.status(409).type(MediaType.APPLICATION_JSON).entity(Map.of("detail", String.valueOf(e.getMessage()))).build();
        }
    }
}
