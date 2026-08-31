package com.aengine.util.collection;

/**
 * 双向链表，非线程安全
 */
public class LinkedList<E> {

    private int count;
    private Node<E> head;
    private Node<E> tail;

    public static class Node<E> {
        private final E item;
        private Node<E> next;
        private Node<E> prev;

        Node(Node<E> prev, E element, Node<E> next) {
            this.item = element;
            this.next = next;
            this.prev = prev;
        }

        private Node<E> addBefore(E element) {
            Node<E> node = new Node<>(prev, element, this);
            prev.next = node;
            prev = node;
            return node;
        }

        private Node<E> addAfter(E element) {
            Node<E> node = new Node<>(this, element, next);
            next.prev = node;
            next = node;
            return node;
        }

        public E getItem() {
            return item;
        }

        private Node<E> getNext() {
            return next;
        }

        private Node<E> getPrev() {
            return prev;
        }
    }

    public LinkedList() {
        this.head = new Node<>(null, null, null);
        this.tail = new Node<>(null, null, null);
        this.head.next = this.tail;
        this.tail.prev = this.head;
    }

    public Node<E> addFirst(E element) {
        Node<E> next = this.head.next;
        Node<E> node = new Node<>(this.head, element, next);
        this.head.next = node;
        next.prev = node;
        this.count++;
        return node;
    }

    public Node<E> addLast(E element) {
        Node<E> prev = this.tail.prev;
        Node<E> node = new Node<>(prev, element, this.tail);
        prev.next = node;
        this.tail.prev = node;
        this.count++;
        return node;
    }

    public Node<E> addBefore(Node<E> node, E element) {
        this.count++;
        return node.addBefore(element);
    }

    public Node<E> addAfter(Node<E> node, E element) {
        this.count++;
        return node.addAfter(element);
    }

    public void remove(Node<E> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        this.count--;
    }

    public Node<E> getFirst() {
        if (this.head.next == this.tail)
            return null;
        return this.head.next;
    }

    public Node<E> getLast() {
        if (this.tail.prev == this.head)
            return null;
        return this.tail.prev;
    }

    public Node<E> getNext(Node<E> node) {
        if (node.getNext() == this.tail)
            return null;
        return node.getNext();
    }

    public Node<E> getPrev(Node<E> node) {
        if (node.getPrev() == this.head)
            return null;
        return node.getPrev();
    }

    public int size() {
        return count;
    }

    public void clear() {
    	head.next = tail;
    	tail.prev = head;
    	count = 0;
    }
}
