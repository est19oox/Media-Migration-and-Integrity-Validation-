package mmiv.user_access_service.repository;

import mmiv.user_access_service.model.DestinationAccess;
import mmiv.user_access_service.model.Permission;
import mmiv.user_access_service.model.User;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryUserRepositoryTest {

    @Test
    void shouldFindExistingUserById() {

        User alice = new User(
                "U001",
                List.of(
                        new DestinationAccess(
                                "mmiv-bucket-a",
                                Permission.WRITE)));

        InMemoryUserRepository repository = new InMemoryUserRepository();

        repository.save(alice);

        Optional<User> result = repository.findById("U001");

        assertTrue(result.isPresent());
        assertEquals(
                "U001",
                result.get().getUserId());
    }
}